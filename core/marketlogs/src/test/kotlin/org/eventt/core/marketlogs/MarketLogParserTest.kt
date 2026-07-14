package org.eventt.core.marketlogs

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

// Fixtures transcribed verbatim from real EVE Marketlogs export files.
private const val ORDER_BOOK_HEADER =
    "price,volRemaining,typeID,range,orderID,volEntered,minVolume,bid,issueDate,duration,stationID,regionID,solarSystemID,jumps,"
private const val ORDER_BOOK_ROW =
    "4690000.0,1500.0,44992,32767,7374815516,1500,1,False,2026-07-09 02:25:23.000,14,60008494,19000001,30002187,11,"
private const val ORDER_BOOK_ROW_2 =
    "4695000.0,113.0,44992,32767,7374801799,200,1,False,2026-07-09 01:58:29.000,90,60003760,19000001,30000142,0,"

class MarketLogParserTest {
    // ─── detectKind ─────────────────────────────────────────────────────────

    @Test
    fun `detectKind recognizes the order book header`() {
        MarketLogParser.detectKind(ORDER_BOOK_HEADER) shouldBe MarketLogFileKind.ORDER_BOOK
    }

    @Test
    fun `detectKind returns UNKNOWN for an unrelated file`() {
        MarketLogParser.detectKind("foo,bar,baz,") shouldBe MarketLogFileKind.UNKNOWN
    }

    // ─── reformatIssueDate ──────────────────────────────────────────────────

    @Test
    fun `reformatIssueDate converts the export's space-separated date to ISO-with-T`() {
        MarketLogParser.reformatIssueDate("2026-07-09 02:57:41.000") shouldBe "2026-07-09T02:57:41Z"
    }

    // ─── parseOrderBook ─────────────────────────────────────────────────────

    @Test
    fun `parseOrderBook parses buy and sell rows and the jumps column`() {
        val rows = MarketLogParser.parseOrderBook(listOf(ORDER_BOOK_HEADER, ORDER_BOOK_ROW, ORDER_BOOK_ROW_2))
        rows.size shouldBe 2
        rows.all { it.typeId == 44992 } shouldBe true
        rows.all { it.regionId == 19000001 } shouldBe true
        rows.all { !it.isBuyOrder } shouldBe true // bid="False" for both sample rows

        val first = rows[0]
        first.orderId shouldBe 7374815516L
        first.price shouldBe 4690000.0
        first.volRemaining shouldBe 1500
        first.jumps shouldBe 11

        val second = rows[1]
        second.jumps shouldBe 0
        second.stationId shouldBe 60003760L
    }

    // ─── robustness ─────────────────────────────────────────────────────────

    @Test
    fun `parseOrderBook tolerates blank lines and returns empty list for a header-only file`() {
        MarketLogParser.parseOrderBook(listOf(ORDER_BOOK_HEADER)) shouldBe emptyList()
        MarketLogParser.parseOrderBook(listOf(ORDER_BOOK_HEADER, "", ORDER_BOOK_ROW, "")).size shouldBe 1
    }

    @Test
    fun `parseOrderBook skips a malformed row instead of throwing`() {
        val malformed = "not,enough,columns"
        val rows = MarketLogParser.parseOrderBook(listOf(ORDER_BOOK_HEADER, ORDER_BOOK_ROW, malformed))
        rows.size shouldBe 1
    }
}
