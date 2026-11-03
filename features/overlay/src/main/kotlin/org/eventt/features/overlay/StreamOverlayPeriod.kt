package org.eventt.features.overlay

import org.eventt.core.database.StaticDataDao
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime

private const val PERIOD_SETTING = "stream_overlay.period"
private const val START_SETTING = "stream_overlay.start"

/** What the overlay totals up: the live session, or the last/next N days. */
enum class OverlayPeriod(
    val key: String,
    val days: Long?,
) {
    SESSION("session", null),
    DAY("24h", 1),
    WEEK("7d", 7),
    MONTH("30d", 30),
}

/**
 * The time range the overlay counts sales in. [until] is set only for a fixed window that has
 * already run its course; [elapsedSeconds] is how far into the window "now" is.
 */
data class OverlayWindow(
    val period: OverlayPeriod,
    val since: LocalDateTime,
    val until: LocalDateTime?,
    val spanSeconds: Long,
    val elapsedSeconds: Long,
    // Fixed start (a dated challenge) rather than a rolling "last N days".
    val fixed: Boolean,
)

object StreamOverlayConfig {
    var period: OverlayPeriod
        get() = OverlayPeriod.entries.find { it.key == StaticDataDao.getSetting(PERIOD_SETTING) } ?: OverlayPeriod.SESSION
        set(value) = StaticDataDao.setSetting(PERIOD_SETTING, value.key)

    /** Day a fixed window starts (midnight local); null = rolling. */
    var fixedStart: LocalDate?
        get() = StaticDataDao.getSetting(START_SETTING)?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
        set(value) = StaticDataDao.setSetting(START_SETTING, value?.toString() ?: "")

    fun window(now: LocalDateTime = LocalDateTime.now()): OverlayWindow = window(period, fixedStart, StreamOverlaySession.startedAt, now)

    fun window(
        period: OverlayPeriod,
        fixedStart: LocalDate?,
        sessionStart: LocalDateTime,
        now: LocalDateTime,
    ): OverlayWindow {
        val days = period.days
        if (days == null) {
            val elapsed = Duration.between(sessionStart, now).seconds
            return OverlayWindow(period, sessionStart, null, elapsed, elapsed, fixed = false)
        }
        val span = Duration.ofDays(days)
        if (fixedStart == null) return OverlayWindow(period, now - span, null, span.seconds, span.seconds, fixed = false)
        val since = fixedStart.atStartOfDay()
        val end = since + span
        val elapsed = Duration.between(since, minOf(now, end)).seconds.coerceAtLeast(0)
        return OverlayWindow(period, since, end.takeIf { now >= it }, span.seconds, elapsed, fixed = true)
    }
}
