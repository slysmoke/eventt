package org.eventt.features.p2pmarket

import java.util.Locale

/**
 * Renders a duration in seconds down to second precision — "3d 04:22:06" past the first day,
 * "04:22:06" under it. Negative input (already-elapsed expirations) clamps to zero rather than
 * showing a negative countdown.
 */
internal fun formatDurationShort(totalSeconds: Long): String {
    val s = totalSeconds.coerceAtLeast(0)
    val days = s / 86400
    val hours = (s % 86400) / 3600
    val minutes = (s % 3600) / 60
    val seconds = s % 60
    val hms = String.format(Locale.US, "%02d:%02d:%02d", hours, minutes, seconds)
    return if (days > 0) "${days}d $hms" else hms
}
