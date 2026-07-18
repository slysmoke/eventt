package org.eventt.core.esi

import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockkObject
import io.mockk.unmockkObject
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.eventt.core.auth.SsoAuthManager
import org.eventt.core.cache.EsiCacheManager
import org.eventt.core.database.DatabaseManager
import org.eventt.core.queue.RequestQueueManager
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class EsiClientTest {
    companion object {
        @BeforeAll
        @JvmStatic
        fun initInMemoryDb() {
            DatabaseManager.initialize(":memory:")
        }

        @AfterAll
        @JvmStatic
        fun restoreBaseUrl() {
            EsiClient.esiBaseUrl = "https://esi.evetech.net/latest"
        }
    }

    private lateinit var server: MockWebServer

    @BeforeEach
    fun setUp() {
        server = MockWebServer()
        server.start()
        EsiClient.esiBaseUrl = server.url("").toString().removeSuffix("/")
        EsiCacheManager.clearAll()
        RequestQueueManager.clearAll()
        mockkObject(SsoAuthManager)
        // Otherwise the first getRaw() call in the suite triggers a real fetch of the real ESI's
        // /meta/status before hitting the MockWebServer.
        mockkObject(EsiStatusService)
        every { EsiStatusService.isHealthy(any(), any()) } returns true
    }

    @AfterEach
    fun tearDown() {
        server.shutdown()
        unmockkObject(SsoAuthManager)
        unmockkObject(EsiStatusService)
    }

    @Test
    fun `a fresh response is cached so a second call doesn't hit the server again`() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"foo":1}""").setHeader("Cache-Control", "max-age=300"))

        val (first, _) = EsiClient.getRaw("/test-fresh-cache/")
        val (second, _) = EsiClient.getRaw("/test-fresh-cache/")

        first shouldBe """{"foo":1}"""
        second shouldBe first
        server.requestCount shouldBe 1
    }

    @Test
    fun `a stale cache entry sends a conditional request and a 304 refreshes it without a new body`() {
        val endpoint = "/test-conditional/"
        val fullParams = mapOf("datasource" to "tranquility")
        EsiCacheManager.save(
            endpoint,
            fullParams,
            data = """{"cached":true}""",
            expiresAtMs = System.currentTimeMillis() - 1_000,
            etag = "\"the-etag\"",
            lastModified = "Mon, 01 Jan 2024 00:00:00 GMT",
        )
        server.enqueue(MockResponse().setResponseCode(304).setHeader("Cache-Control", "max-age=600"))

        val (body, _) = EsiClient.getRaw(endpoint)

        body shouldBe """{"cached":true}"""
        val sentRequest = server.takeRequest()
        sentRequest.getHeader("If-None-Match") shouldBe "\"the-etag\""
        sentRequest.getHeader("If-Modified-Since") shouldBe "Mon, 01 Jan 2024 00:00:00 GMT"
        (EsiCacheManager.getExpiry(endpoint, fullParams)!! > System.currentTimeMillis()) shouldBe true
    }

    @Test
    fun `a 401 with a character id refreshes the token and retries once`() {
        every { SsoAuthManager.ensureTokenFresh(42) } returns "fresh-token"
        server.enqueue(MockResponse().setResponseCode(401))
        server.enqueue(MockResponse().setResponseCode(200).setBody("ok-body").setHeader("Cache-Control", "max-age=300"))

        val (body, _) = EsiClient.getRaw("/test-401/", accessToken = "stale-token", characterId = 42)

        body shouldBe "ok-body"
        server.requestCount shouldBe 2
        server.takeRequest().getHeader("Authorization") shouldBe "Bearer stale-token"
        server.takeRequest().getHeader("Authorization") shouldBe "Bearer fresh-token"
    }

    @Test
    fun `a failing request falls back to stale cached data instead of throwing`() {
        val endpoint = "/test-fallback/"
        val fullParams = mapOf("datasource" to "tranquility")
        EsiCacheManager.save(
            endpoint,
            fullParams,
            data = """{"stale":true}""",
            expiresAtMs = System.currentTimeMillis() - 1_000,
        )
        // EsiThrottleInterceptor retries 5xx up to 3 times (4 total attempts) before giving up.
        repeat(4) { server.enqueue(MockResponse().setResponseCode(503)) }

        val (body, _) = EsiClient.getRaw(endpoint)

        body shouldBe """{"stale":true}"""
    }

    @Test
    fun `getRaw serves stale cache without hitting the server when ESI reports the endpoint degraded`() {
        val endpoint = "/test-degraded-stale/"
        val fullParams = mapOf("datasource" to "tranquility")
        EsiCacheManager.save(endpoint, fullParams, data = """{"stale":true}""", expiresAtMs = System.currentTimeMillis() - 1_000)
        every { EsiStatusService.isHealthy(any(), any()) } returns false

        val (body, _) = EsiClient.getRaw(endpoint)

        body shouldBe """{"stale":true}"""
        server.requestCount shouldBe 0
    }

    @Test
    fun `getRaw throws EsiDegradedException when the endpoint is degraded and there is no cached data`() {
        every { EsiStatusService.isHealthy(any(), any()) } returns false

        org.junit.jupiter.api.Assertions.assertThrows(EsiDegradedException::class.java) {
            EsiClient.getRaw("/test-degraded-miss/")
        }
    }

    @Test
    fun `getCharacterOrders fetches every page and merges them into one result`() {
        every { SsoAuthManager.ensureTokenFresh(99) } returns "token-99"
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""[{"order_id":1}]""")
                .setHeader("X-Pages", "2")
                .setHeader("Cache-Control", "max-age=300"),
        )
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""[{"order_id":2}]""")
                .setHeader("X-Pages", "2")
                .setHeader("Cache-Control", "max-age=300"),
        )

        val orders = EsiClient.getCharacterOrders(99)

        orders.map { (it["order_id"] as Number).toInt() } shouldContainExactlyInAnyOrder listOf(1, 2)
        server.requestCount shouldBe 2
    }

    @Test
    fun `a second call for the same paginated resource is served from the merged cache`() {
        every { SsoAuthManager.ensureTokenFresh(99) } returns "token-99"
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""[{"order_id":1}]""")
                .setHeader("X-Pages", "1")
                .setHeader("Cache-Control", "max-age=300"),
        )

        EsiClient.getCharacterOrders(99)
        val second = EsiClient.getCharacterOrders(99)

        second.map { (it["order_id"] as Number).toInt() } shouldContainExactlyInAnyOrder listOf(1)
        server.requestCount shouldBe 1
    }

    private val sampleFitting =
        EsiClient.FittingRequestDto(
            name = "Split 1 - 1.00B ISK",
            description = "Generated by eventt Cargo Splitter",
            shipTypeId = 20185,
            items = listOf(EsiClient.FittingItemDto(typeId = 34, quantity = 100, flag = "Cargo")),
        )

    @Test
    fun `postCharacterFitting posts the fitting and returns the new fitting id`() {
        every { SsoAuthManager.ensureTokenFresh(7) } returns "token-7"
        server.enqueue(MockResponse().setResponseCode(201).setBody("""{"fitting_id":555}"""))

        val fittingId = EsiClient.postCharacterFitting(7, sampleFitting)

        fittingId shouldBe 555
        val sent = server.takeRequest()
        sent.method shouldBe "POST"
        sent.getHeader("Authorization") shouldBe "Bearer token-7"
        sent.body.readUtf8().contains("\"ship_type_id\":20185") shouldBe true
    }

    @Test
    fun `postCharacterFitting retries once on 401 with a refreshed token`() {
        every { SsoAuthManager.ensureTokenFresh(7) } returns "token-7" andThen "fresh-token-7"
        server.enqueue(MockResponse().setResponseCode(401))
        server.enqueue(MockResponse().setResponseCode(201).setBody("""{"fitting_id":556}"""))

        val fittingId = EsiClient.postCharacterFitting(7, sampleFitting)

        fittingId shouldBe 556
        server.requestCount shouldBe 2
        server.takeRequest().getHeader("Authorization") shouldBe "Bearer token-7"
        server.takeRequest().getHeader("Authorization") shouldBe "Bearer fresh-token-7"
    }

    @Test
    fun `postCharacterFitting throws IOException on a non-2xx response`() {
        // 400 is a plain client error — EsiThrottleInterceptor only retries 429/420/5xx, so this
        // returns as-is on the first attempt (unlike 420, which would trigger a 60s real backoff).
        every { SsoAuthManager.ensureTokenFresh(7) } returns "token-7"
        server.enqueue(MockResponse().setResponseCode(400).setBody("invalid fitting"))

        try {
            EsiClient.postCharacterFitting(7, sampleFitting)
            error("expected postCharacterFitting to throw")
        } catch (e: java.io.IOException) {
            e.message?.contains("400") shouldBe true
        }
    }
}
