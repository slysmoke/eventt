package org.eventt.core.http

import okhttp3.Interceptor
import okhttp3.Response

/** Sets the User-Agent ESI requires on every request (mandatory per CCP's best-practices docs). */
class UserAgentInterceptor(
    private val userAgent: () -> String,
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request =
            chain
                .request()
                .newBuilder()
                .header("User-Agent", userAgent())
                .build()
        return chain.proceed(request)
    }
}

/**
 * Honors ESI's own rate-limit signals instead of guessing a fixed request rate — see
 * https://developers.eveonline.com/docs/services/esi/rate-limiting/ and .../best-practices/.
 *
 * - HTTP 429 (per-route-group limiter): sleeps exactly `Retry-After` seconds, then retries.
 * - HTTP 420 (legacy global error limit exceeded): no reset header is published for it, so
 *   backs off a fixed window before retrying.
 * - `X-ESI-Error-Limit-Remain` / `-Reset` (legacy): once the error budget is nearly spent,
 *   pauses further requests through this client until it resets — before we ever get a 420.
 * - `X-Ratelimit-Remaining` / `X-Ratelimit-Limit` (newer per-route-group budget): once a
 *   response reports a route group is nearly exhausted, adds a short cooldown so the bucket
 *   gets a chance to refill instead of racing it down to zero.
 *
 * The cooldown is shared across all calls made through one interceptor instance (there's only
 * ever one in production, via EveHttpClient), since the legacy error limit is global to the
 * application regardless of which route triggered it — an instance field rather than a
 * companion/static one, so separate instances (e.g. in tests) don't share cooldown state.
 * Retries are bounded; other 4xx responses (real client errors) are returned as-is.
 */
class EsiThrottleInterceptor(
    private val maxRetries: Int = 3,
    private val legacy420BackoffMs: Long = LEGACY_420_BACKOFF_MS,
    private val rateLimitCooldownMs: Long = RATE_LIMIT_COOLDOWN_MS,
) : Interceptor {
    private companion object {
        const val LOW_ERROR_BUDGET = 5
        const val LOW_RATE_LIMIT_MARGIN = 0.1
        const val RATE_LIMIT_COOLDOWN_MS = 2_000L
        const val LEGACY_420_BACKOFF_MS = 60_000L
        const val DEFAULT_RETRY_AFTER_S = 5L
        val JITTER_MS_RANGE = 0L..1_500L
    }

    @Volatile
    private var cooldownUntilMs: Long = 0L

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()

        repeat(maxRetries + 1) { attempt ->
            awaitCooldown()

            val response = chain.proceed(request)
            applyServerSignals(response)

            val shouldRetry =
                attempt < maxRetries &&
                    when {
                        response.code == 429 -> {
                            val retryAfterS = response.header("Retry-After")?.toLongOrNull() ?: DEFAULT_RETRY_AFTER_S
                            cooldownUntilMs = System.currentTimeMillis() + retryAfterS * 1000
                            true
                        }
                        response.code == 420 -> {
                            cooldownUntilMs = System.currentTimeMillis() + legacy420BackoffMs
                            true
                        }
                        response.code >= 500 -> true
                        else -> false
                    }

            if (shouldRetry) {
                response.close()
                // 429/420 already set cooldownUntilMs above — awaitCooldown() on the next
                // iteration handles the wait. 5xx has no such signal, so back off here instead.
                if (response.code >= 500) {
                    Thread.sleep(500L * (1L shl attempt))
                }
                return@repeat
            }

            return response
        }

        // Unreachable: every iteration above either retries (`return@repeat`) or returns.
        error("EsiThrottleInterceptor: exhausted retries without returning a response")
    }

    private fun awaitCooldown() {
        val wait = cooldownUntilMs - System.currentTimeMillis()
        if (wait > 0) {
            // Jitter avoids a thundering herd: with several concurrent calls through this one
            // client (bulk analysis runs with 4-10x parallelism), all of them would otherwise
            // read the same cooldownUntilMs and wake to retry in the same instant, immediately
            // re-consuming whatever budget had refilled and re-triggering the same 420/429.
            Thread.sleep(wait + JITTER_MS_RANGE.random())
        }
    }

    private fun applyServerSignals(response: Response) {
        val errorRemain = response.header("X-ESI-Error-Limit-Remain")?.toIntOrNull()
        if (errorRemain != null && errorRemain <= LOW_ERROR_BUDGET) {
            val resetS = response.header("X-ESI-Error-Limit-Reset")?.toLongOrNull() ?: DEFAULT_RETRY_AFTER_S
            cooldownUntilMs = maxOf(cooldownUntilMs, System.currentTimeMillis() + resetS * 1000)
        }

        val rlRemaining = response.header("X-Ratelimit-Remaining")?.toIntOrNull()
        // "X-Ratelimit-Limit" is formatted like "150/15m" — only the token count is needed here.
        val rlLimit = response.header("X-Ratelimit-Limit")?.substringBefore("/")?.toIntOrNull()
        if (rlRemaining != null &&
            rlLimit != null &&
            rlLimit > 0 &&
            rlRemaining.toDouble() / rlLimit <= LOW_RATE_LIMIT_MARGIN
        ) {
            cooldownUntilMs = maxOf(cooldownUntilMs, System.currentTimeMillis() + rateLimitCooldownMs)
        }
    }
}
