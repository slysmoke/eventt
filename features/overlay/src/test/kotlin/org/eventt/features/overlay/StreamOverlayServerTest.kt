package org.eventt.features.overlay

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.eventt.core.database.DatabaseManager
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

class StreamOverlayServerTest {
    companion object {
        @BeforeAll
        @JvmStatic
        fun setUp() {
            DatabaseManager.close()
            DatabaseManager.initialize(":memory:")
            StreamOverlayServer.start()
        }

        @AfterAll
        @JvmStatic
        fun tearDown() {
            StreamOverlayServer.stop()
        }
    }

    private val client = HttpClient.newHttpClient()

    private fun get(path: String): HttpResponse<String> =
        client.send(
            HttpRequest.newBuilder(URI.create("http://127.0.0.1:${StreamOverlayServer.PORT}$path")).GET().build(),
            HttpResponse.BodyHandlers.ofString(),
        )

    @Test
    fun `serves the overlay page`() {
        val response = get("/")
        response.statusCode() shouldBe 200
        response.body() shouldContain "background: transparent"
        response.body() shouldContain "/api/stats"
    }

    @Test
    fun `serves live stats as JSON on an empty database`() {
        val response = get("/api/stats")
        response.statusCode() shouldBe 200
        response.body() shouldContain "\"tradesSession\":0"
        response.body() shouldContain "\"profitSession\":0.0"
        response.body() shouldContain "\"relistsSession\":0"
    }
}
