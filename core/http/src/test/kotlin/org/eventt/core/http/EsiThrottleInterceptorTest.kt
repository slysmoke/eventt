package org.eventt.core.http

import io.kotest.matchers.shouldBe
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class EsiThrottleInterceptorTest {
    private lateinit var server: MockWebServer
    private lateinit var client: OkHttpClient

    @BeforeEach
    fun setUp() {
        server = MockWebServer()
        server.start()
        // Tiny overrides so the 420/rate-limit paths don't make the test wait for real.
        client =
            OkHttpClient
                .Builder()
                .addInterceptor(EsiThrottleInterceptor(maxRetries = 3, legacy420BackoffMs = 5, rateLimitCooldownMs = 5))
                .build()
    }

    @AfterEach
    fun tearDown() {
        server.shutdown()
    }

    private fun call(): okhttp3.Response = client.newCall(Request.Builder().url(server.url("/")).build()).execute()

    @Test
    fun `429 is retried once Retry-After elapses`() {
        server.enqueue(MockResponse().setResponseCode(429).setHeader("Retry-After", "0"))
        server.enqueue(MockResponse().setResponseCode(200).setBody("ok"))

        val response = call()

        response.code shouldBe 200
        server.requestCount shouldBe 2
    }

    @Test
    fun `420 backs off then retries`() {
        server.enqueue(MockResponse().setResponseCode(420))
        server.enqueue(MockResponse().setResponseCode(200).setBody("ok"))

        val response = call()

        response.code shouldBe 200
        server.requestCount shouldBe 2
    }

    @Test
    fun `5xx is retried with backoff`() {
        server.enqueue(MockResponse().setResponseCode(503))
        server.enqueue(MockResponse().setResponseCode(200).setBody("ok"))

        val response = call()

        response.code shouldBe 200
        server.requestCount shouldBe 2
    }

    @Test
    fun `a real client error is returned as-is, not retried`() {
        server.enqueue(MockResponse().setResponseCode(404))

        val response = call()

        response.code shouldBe 404
        server.requestCount shouldBe 1
    }

    @Test
    fun `a low error-limit budget does not break the next request`() {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("X-ESI-Error-Limit-Remain", "1")
                .setHeader("X-ESI-Error-Limit-Reset", "0")
                .setBody("ok"),
        )
        server.enqueue(MockResponse().setResponseCode(200).setBody("ok"))

        call().code shouldBe 200
        call().code shouldBe 200
        server.requestCount shouldBe 2
    }

    @Test
    fun `gives up and returns the failing response after exhausting retries`() {
        repeat(4) { server.enqueue(MockResponse().setResponseCode(503)) }

        val response = call()

        response.code shouldBe 503
        server.requestCount shouldBe 4
    }
}
