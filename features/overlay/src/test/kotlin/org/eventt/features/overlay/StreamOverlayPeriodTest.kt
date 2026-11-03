package org.eventt.features.overlay

import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.eventt.features.orders.CostBasisService
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.LocalDateTime

class StreamOverlayPeriodTest {
    private val now = LocalDateTime.of(2026, 11, 10, 15, 0)
    private val sessionStart = now.minusHours(2)

    @Test
    fun `session counts from the session start`() {
        val w = StreamOverlayConfig.window(OverlayPeriod.SESSION, LocalDate.of(2026, 11, 1), sessionStart, now)
        w.since shouldBe sessionStart
        w.elapsedSeconds shouldBe 7200
        w.until.shouldBeNull()
    }

    @Test
    fun `rolling covers the last N days up to now`() {
        val w = StreamOverlayConfig.window(OverlayPeriod.WEEK, null, sessionStart, now)
        w.since shouldBe now.minusDays(7)
        w.elapsedSeconds shouldBe 7 * 86_400
        w.fixed shouldBe false
    }

    @Test
    fun `a fixed window starts at midnight and stops counting after N days`() {
        val running = StreamOverlayConfig.window(OverlayPeriod.MONTH, LocalDate.of(2026, 11, 8), sessionStart, now)
        running.since shouldBe LocalDateTime.of(2026, 11, 8, 0, 0)
        running.elapsedSeconds shouldBe 2 * 86_400 + 15 * 3600
        running.until.shouldBeNull()

        val done = StreamOverlayConfig.window(OverlayPeriod.DAY, LocalDate.of(2026, 11, 8), sessionStart, now)
        done.until shouldBe LocalDateTime.of(2026, 11, 9, 0, 0)
        done.elapsedSeconds shouldBe 86_400
    }

    @Test
    fun `the period chart steps up with each confirmed sale and ends on the live total`() {
        val w = StreamOverlayConfig.window(OverlayPeriod.WEEK, LocalDate.of(2026, 11, 9), sessionStart, now)

        fun sale(
            date: String,
            profit: Double,
        ) = CostBasisService.RealizedSellTx(date, 34, 1, 10.0, 5.0, profit, 0.0)
        val series = StreamOverlayServer.periodSeries(listOf(sale("2026-11-10T01:00:00", 3.0), sale("2026-11-09T12:00:00", 2.0)), w, 9.0)

        series shouldBe
            listOf(
                OverlayPoint(0, 0.0),
                OverlayPoint(12 * 3600, 2.0),
                OverlayPoint(25 * 3600, 5.0),
                // Unconfirmed fills on top of the wallet total.
                OverlayPoint(w.elapsedSeconds, 9.0),
            )
    }
}
