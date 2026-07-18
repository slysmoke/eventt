package org.eventt.core.esi

import io.kotest.matchers.shouldBe
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class EsiStatusServiceTest {
    private lateinit var server: MockWebServer

    @BeforeEach
    fun setUp() {
        server = MockWebServer()
        server.start()
        EsiStatusService.statusUrl = server.url("/meta/status").toString()
        EsiStatusService.resetForTest()
    }

    @AfterEach
    fun tearDown() {
        server.shutdown()
        EsiStatusService.resetForTest()
    }

    private val statusBody =
        """
        {"routes":[
          {"method":"GET","path":"/characters/{character_id}/wallet/journal","status":"OK"},
          {"method":"GET","path":"/characters/{character_id}/orders","status":"degraded"}
        ]}
        """.trimIndent()

    @Test
    fun `isHealthy matches a real numeric path against ESI's templated placeholder`() {
        server.enqueue(MockResponse().setResponseCode(200).setBody(statusBody))

        EsiStatusService.isHealthy("GET", "/characters/95465499/wallet/journal/") shouldBe true
    }

    @Test
    fun `isHealthy is false for a route ESI reports as not OK`() {
        server.enqueue(MockResponse().setResponseCode(200).setBody(statusBody))

        EsiStatusService.isHealthy("GET", "/characters/95465499/orders/") shouldBe false
    }

    @Test
    fun `isHealthy fails open for a route absent from the status list`() {
        server.enqueue(MockResponse().setResponseCode(200).setBody(statusBody))

        EsiStatusService.isHealthy("GET", "/markets/10000002/orders/") shouldBe true
    }

    @Test
    fun `isHealthy fails open when the status fetch itself fails`() {
        server.enqueue(MockResponse().setResponseCode(503))

        EsiStatusService.isHealthy("GET", "/characters/95465499/orders/") shouldBe true
    }
}
