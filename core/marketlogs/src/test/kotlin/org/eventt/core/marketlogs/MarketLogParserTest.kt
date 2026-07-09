package org.eventt.core.marketlogs

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

// Fixtures transcribed verbatim from real EVE Marketlogs export files.
private const val MY_ORDERS_HEADER =
    "orderID,typeID,charID,charName,regionID,regionName,solarSystemID,solarSystemName,stationID,stationName," +
        "range,bid,price,volEntered,volRemaining,minVolume,issueDate,orderState,duration,escrow,isCorp," +
        "accountID,accountOwnerID,accountKey,"
private const val MY_ORDERS_ROW =
    "7372477240,33904,2113351129,Sasha Winston,10000002,The Forge,30000142,Jita,60003760," +
        "Jita IV - Moon 4 - Caldari Navy Assembly Plant,-1,True,310500000.0,2,2.0,1,2026-07-09 02:57:41.000,0," +
        "90,621000000.0,False,78024504,2113351129,1000,"

private const val CORP_ORDERS_HEADER =
    "orderID,typeID,charID,charName,regionID,regionName,stationID,stationName,range,bid,price,volEntered," +
        "volRemaining,issueDate,orderState,minVolume,accountID,duration,isCorp,solarSystemID,solarSystemName,escrow,keyID,"
private const val CORP_ORDERS_ROW =
    "7374839444,40519,2113351129,Sasha Winston,10000002,The Forge,60003760," +
        "Jita IV - Moon 4 - Caldari Navy Assembly Plant,32767,True,1.0,1,1.0,2026-07-09 03:20:53.000,0,1," +
        "81257669,1,True,30000142,Jita,1.0,1000,"

private const val ORDER_BOOK_HEADER =
    "price,volRemaining,typeID,range,orderID,volEntered,minVolume,bid,issueDate,duration,stationID,regionID,solarSystemID,jumps,"
private const val ORDER_BOOK_ROW =
    "4690000.0,1500.0,44992,32767,7374815516,1500,1,False,2026-07-09 02:25:23.000,14,60008494,19000001,30002187,11,"
private const val ORDER_BOOK_ROW_2 =
    "4695000.0,113.0,44992,32767,7374801799,200,1,False,2026-07-09 01:58:29.000,90,60003760,19000001,30000142,0,"

class MarketLogParserTest {
    // ─── detectKind ─────────────────────────────────────────────────────────

    @Test
    fun `detectKind recognizes the My Orders header`() {
        MarketLogParser.detectKind(MY_ORDERS_HEADER) shouldBe MarketLogFileKind.MY_ORDERS
    }

    @Test
    fun `detectKind recognizes the Corporation Orders header`() {
        MarketLogParser.detectKind(CORP_ORDERS_HEADER) shouldBe MarketLogFileKind.CORP_ORDERS
    }

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

    // ─── parseOrderLog (My Orders) ──────────────────────────────────────────

    @Test
    fun `parseOrderLog parses a My Orders row by header name`() {
        val rows = MarketLogParser.parseOrderLog(listOf(MY_ORDERS_HEADER, MY_ORDERS_ROW))
        rows.size shouldBe 1
        val row = rows.single()
        row.orderId shouldBe 7372477240L
        row.typeId shouldBe 33904
        row.charId shouldBe 2113351129
        row.charName shouldBe "Sasha Winston"
        row.regionId shouldBe 10000002
        row.stationId shouldBe 60003760L
        row.isBuyOrder shouldBe true // bid="True"
        row.price shouldBe 310500000.0
        row.volEntered shouldBe 2
        row.volRemaining shouldBe 2 // "2.0" -> Int
        row.minVolume shouldBe 1
        row.issuedIso shouldBe "2026-07-09T02:57:41Z"
        row.orderState shouldBe 0
        row.duration shouldBe 90
        row.escrow shouldBe 621000000.0
        row.isCorp shouldBe false
    }

    // ─── parseOrderLog (Corporation Orders) ─────────────────────────────────

    @Test
    fun `parseOrderLog parses a Corporation Orders row despite its different column order`() {
        val rows = MarketLogParser.parseOrderLog(listOf(CORP_ORDERS_HEADER, CORP_ORDERS_ROW))
        rows.size shouldBe 1
        val row = rows.single()
        row.orderId shouldBe 7374839444L
        row.typeId shouldBe 40519
        row.charId shouldBe 2113351129
        row.regionId shouldBe 10000002
        row.stationId shouldBe 60003760L
        row.isBuyOrder shouldBe true
        row.price shouldBe 1.0
        row.volEntered shouldBe 1
        row.volRemaining shouldBe 1
        row.issuedIso shouldBe "2026-07-09T03:20:53Z"
        row.isCorp shouldBe true // keyID column present -> treated as a corp-file row regardless of isCorp text
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
    fun `parseOrderLog tolerates blank lines and returns empty list for a header-only file`() {
        MarketLogParser.parseOrderLog(listOf(MY_ORDERS_HEADER)) shouldBe emptyList()
        MarketLogParser.parseOrderLog(listOf(MY_ORDERS_HEADER, "", MY_ORDERS_ROW, "")).size shouldBe 1
    }

    @Test
    fun `parseOrderLog skips a malformed row instead of throwing`() {
        val malformed = "not,enough,columns"
        val rows = MarketLogParser.parseOrderLog(listOf(MY_ORDERS_HEADER, MY_ORDERS_ROW, malformed))
        rows.size shouldBe 1
    }
}
