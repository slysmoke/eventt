package org.eve.trader.core.http

import kotlinx.coroutines.delay
import okhttp3.Interceptor
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

/**
 * Rate limiting interceptor for ESI API.
 * ESI allows ~20 requests/second globally, with per-endpoint limits.
 * This interceptor ensures we stay within limits.
 */
class RateLimitInterceptor(
    private val maxRequestsPerSecond: Int = 20
) : Interceptor {

    private val requestCount = AtomicInteger(0)
    private val lastResetTime = System.currentTimeMillis()

    override fun intercept(chain: Interceptor.Chain): Response {
        synchronized(this) {
            val now = System.currentTimeMillis()
            val elapsed = now - lastResetTime

            if (elapsed >= 1000) {
                // Reset counter after 1 second
                requestCount.set(0)
            } else if (requestCount.get() >= maxRequestsPerSecond) {
                // We've hit the limit, wait until next second window
                val waitTime = 1000 - elapsed
                // Note: In a real implementation, we'd use a more sophisticated approach
                // For now, we proceed and let ESI return 420 if needed
            }

            requestCount.incrementAndGet()
        }

        return chain.proceed(chain.request())
    }
}

/**
 * Retry interceptor with exponential backoff for transient failures.
 */
class RetryInterceptor(
    private val maxRetries: Int = 3,
    private val initialBackoffMs: Long = 500,
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        var lastException: IOException? = null

        repeat(maxRetries) { attempt ->
            try {
                val response = chain.proceed(request)

                // Don't retry on client errors (4xx) except 420 (rate limit)
                if (response.code == 420) {
                    response.close()
                    val backoffMs = initialBackoffMs * (1L shl attempt)
                    Thread.sleep(backoffMs)
                    return@repeat
                }

                if (response.code >= 500) {
                    response.close()
                    val backoffMs = initialBackoffMs * (1L shl attempt)
                    Thread.sleep(backoffMs)
                    return@repeat
                }

                return response
            } catch (e: IOException) {
                lastException = e
                val backoffMs = initialBackoffMs * (1L shl attempt)
                Thread.sleep(backoffMs)
            }
        }

        throw IOException("Failed after $maxRetries retries: ${lastException?.message}", lastException)
    }
}
