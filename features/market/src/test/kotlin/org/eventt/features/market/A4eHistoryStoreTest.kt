package org.eventt.features.market

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.time.LocalDate

class A4eHistoryStoreTest {
    @TempDir
    lateinit var dir: File

    private fun day(s: String) = LocalDate.parse(s).toEpochDay().toInt()

    private fun row(
        d: String,
        isBuy: Boolean,
        amount: Long,
        loc: Long = 60003760,
    ) = A4eRow(day(d), loc, 10000002, isBuy, amount, 1_738_000, 1_791_000, amount * 1_738_600, 7)

    @Test
    fun `codec round-trips rows exactly`() {
        val rows =
            listOf(
                row("2026-09-22", true, 96_678),
                row("2026-09-22", false, 147_165),
                row("2026-09-21", false, 1, loc = 1_035_466_617_946),
                A4eRow(day("2026-09-01"), 60008494, 10000043, true, 0, 0, 999_999_999_999, Long.MAX_VALUE / 4, 0),
            )
        A4eCodec.decode(A4eCodec.encode(rows)).toSet() shouldBe rows.toSet()
    }

    @Test
    fun `weekly file replaces a day a daily file wrote, and a later daily can't undo it`() {
        val store = A4eHistoryStore(File(dir, "a4e.db"))
        store.ingest("2026/daily_22", listOf(4247 to row("2026-09-22", true, 100)), "daily", minDay = 0)
        store.ingest("2026/weekly_39", listOf(4247 to row("2026-09-22", true, 96_678)), "weekly", minDay = 0)
        store.ingest("2026/daily_22_again", listOf(4247 to row("2026-09-22", true, 5)), "daily", minDay = 0)

        store.query(4247, day("2026-09-01"), day("2026-09-30")).map { it.amount } shouldBe listOf(96_678L)
        store.isFileDone("2026/weekly_39") shouldBe true
        store.close()
    }

    @Test
    fun `prune drops whole months before the cutoff`() {
        val store = A4eHistoryStore(File(dir, "a4e.db"))
        store.ingest("f", listOf(4247 to row("2025-08-31", true, 1), 4247 to row("2025-09-01", true, 2)), "weekly", minDay = 0)
        store.prune(202509)
        store.query(4247, day("2025-01-01"), day("2025-12-31")).map { it.amount } shouldBe listOf(2L)
        store.close()
    }

    @Test
    fun `plan prefers weekly files and only fetches uncovered daily days, newest first`() {
        val listing =
            """
            <a href="marketOrderTrades_weekly_2026-38.csv">x</a>
            <a href="marketOrderTrades_weekly_2026-39.csv">x</a>
            <a href="marketOrderTrades_daily_2026-09-26.csv">x</a>
            <a href="marketOrderTrades_daily_2026-09-27.csv">x</a>
            <a href="marketOrderTrades_daily_2026-09-28.csv">x</a>
            """.trimIndent()
        val remote =
            A4eHistorySync.parseListing(2026, listing) +
                // July 2025: before the retention cutoff, never fetched.
                A4eHistorySync.parseListing(2025, """<a href="marketOrderTrades_weekly_2025-30.csv">""")
        // Week 38 of 2026 starts Monday 14 September.
        remote.single { it.name.endsWith("2026-38.csv") }.from shouldBe LocalDate.parse("2026-09-14")

        val todo =
            A4eHistorySync.plan(
                remote,
                cutoff = LocalDate.parse("2025-09-01"),
                isFileDone = { it == "2026/marketOrderTrades_weekly_2026-38.csv" },
                loggedDays = mapOf(day("2026-09-28") to "daily"),
            )
        // 26th/27th are covered by weekly 39; 28th is already stored.
        todo.map { it.name } shouldBe listOf("marketOrderTrades_weekly_2026-39.csv")
    }

    @Test
    fun `parser keeps has_gone 0 rows only and converts prices to cents`() {
        val csv =
            """
            location_id;region_id;type_id;is_buy_order;has_gone;scanDate;amount;high;low;avg;orderNum;iskValue
            60003760;10000002;4247;1;0;2026-09-22;96678;17910.00;17380.00;17227.10;7;1680831040
            60003760;10000002;4247;1;1;2026-09-22;99999;17910.00;17380.00;17227.10;7;1680831040
            """.trimIndent()
        val rows = A4eHistorySync.parseRows(csv.reader().buffered())
        rows.size shouldBe 1
        val (type, r) = rows.single()
        type shouldBe 4247
        r.lowCents shouldBe 1_738_000
        r.iskCents shouldBe 168_083_104_000
        r.isBuy shouldBe true
    }

    @Test
    fun `aggregation sums hubs in a region per side with a volume-weighted price`() {
        val rows =
            listOf(
                A4eRow(day("2026-09-22"), 60003760, 10000002, true, 100, 1_000, 1_200, 110_000, 1),
                A4eRow(day("2026-09-22"), 1_000_000_000_001, 10000002, true, 300, 900, 1_100, 300_000, 1),
                A4eRow(day("2026-09-22"), 60003760, 10000002, false, 50, 1_300, 1_300, 65_000, 1),
                A4eRow(day("2026-09-22"), 60008494, 10000043, true, 999, 1, 1, 999, 1), // other region
            )
        val region = aggregateA4e(rows, stationId = null, regionId = 10000002).getValue("2026-09-22")
        region.bid!!.amount shouldBe 400
        region.bid!!.vwap shouldBe (4_100.0 / 400) // (110_000 + 300_000) cents / 100 / 400
        region.low shouldBe 9.0
        region.high shouldBe 13.0

        aggregateA4e(rows, stationId = 60003760, regionId = 10000002).getValue("2026-09-22").bid!!.amount shouldBe 100
    }
}
