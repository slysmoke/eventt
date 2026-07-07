package org.eventt.core.everef

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorOutputStream
import org.eventt.core.database.DatabaseManager
import org.eventt.core.database.MarketDao
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream

class EveRefServiceNetworkTest {
    companion object {
        @BeforeAll
        @JvmStatic
        fun initInMemoryDb() {
            DatabaseManager.close()
            DatabaseManager.initialize(":memory:")
        }

        @AfterAll
        @JvmStatic
        fun restoreBaseUrl() {
            EveRefService.baseUrl = "https://data.everef.net/market-history"
        }
    }

    private lateinit var server: MockWebServer

    @BeforeEach
    fun setUp() {
        server = MockWebServer()
        server.start()
        EveRefService.baseUrl = server.url("").toString().removeSuffix("/")
    }

    @AfterEach
    fun tearDown() {
        server.shutdown()
        DatabaseManager.transaction {
            createStatement().use {
                it.execute("DELETE FROM market_history")
                it.execute("DELETE FROM everef_downloads")
            }
        }
    }

    // ─── fetchYearIndex ─────────────────────────────────────────────────────

    @Test
    fun `fetchYearIndex parses the file list from a well-formed index response`() {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """
                {"path":"/2024/","files":[
                    {"name":"market-history-2024-01-01.csv.bz2","url":"https://example.com/a.csv.bz2","size":500000}
                ]}
                """.trimIndent(),
            ),
        )

        val files = EveRefService.fetchYearIndex(2024)

        files.single().name shouldBe "market-history-2024-01-01.csv.bz2"
        files.single().url shouldBe "https://example.com/a.csv.bz2"
        files.single().size shouldBe 500_000L
    }

    @Test
    fun `fetchYearIndex skips entries missing a name or url`() {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"files":[{"size":100},{"name":"only-a-name.csv.bz2"},{"url":"https://example.com/x"}]}""",
            ),
        )

        EveRefService.fetchYearIndex(2024).shouldBeEmpty()
    }

    @Test
    fun `fetchYearIndex returns an empty list on a non-2xx response`() {
        server.enqueue(MockResponse().setResponseCode(404))

        EveRefService.fetchYearIndex(2024).shouldBeEmpty()
    }

    @Test
    fun `fetchYearIndex returns an empty list rather than throwing on malformed JSON`() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("not json at all"))

        EveRefService.fetchYearIndex(2024).shouldBeEmpty()
    }

    // ─── downloadAndParse ───────────────────────────────────────────────────

    private fun bzip2(text: String): Buffer {
        val bytes =
            ByteArrayOutputStream()
                .also { baos ->
                    BZip2CompressorOutputStream(baos).use { it.write(text.toByteArray()) }
                }.toByteArray()
        return Buffer().write(bytes)
    }

    @Test
    fun `downloadAndParse decompresses the CSV, saves it as everef history, and marks the file downloaded`() {
        val csv =
            "typeid,regionid,date,highest,average,lowest,volume,ordercount\n" +
                "34,10000002,2024-01-01,6.0,5.5,5.0,1000,20\n"
        server.enqueue(MockResponse().setResponseCode(200).setBody(bzip2(csv)))

        val file =
            EveRefService.FileEntry(
                name = "market-history-2024-01-01.csv.bz2",
                url = server.url("/2024/market-history-2024-01-01.csv.bz2").toString(),
                size = 12345L,
                date = "2024-01-01",
            )

        EveRefService.downloadAndParse(file)

        val history = MarketDao.getHistoryBySource(34, 10000002, source = "everef")
        history.single().date shouldBe "2024-01-01"
        EveRefDao.getDownloadedDates() shouldBe listOf("2024-01-01")
    }

    @Test
    fun `downloadAndParse throws on a non-2xx response`() {
        server.enqueue(MockResponse().setResponseCode(500))
        server.enqueue(MockResponse().setResponseCode(500))
        server.enqueue(MockResponse().setResponseCode(500))
        server.enqueue(MockResponse().setResponseCode(500))
        val file = EveRefService.FileEntry("x.csv.bz2", server.url("/x").toString(), 1L, "2024-01-02")

        assertThrows(Exception::class.java) { EveRefService.downloadAndParse(file) }
    }

    @Test
    fun `downloadAndParse throws when the CSV header doesn't contain the required columns`() {
        server.enqueue(MockResponse().setResponseCode(200).setBody(bzip2("not,the,right,columns\n1,2,3,4\n")))
        val file = EveRefService.FileEntry("x.csv.bz2", server.url("/x").toString(), 1L, "2024-01-03")

        assertThrows(Exception::class.java) { EveRefService.downloadAndParse(file) }
    }
}
