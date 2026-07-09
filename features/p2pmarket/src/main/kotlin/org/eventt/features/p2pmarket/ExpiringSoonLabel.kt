package org.eventt.features.p2pmarket

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable

private const val EXPIRING_SOON_SECONDS = 48L * 3600

/** Shown once an order is within 48h of its NIP-40 expiration, nudging the poster to renew before it silently drops off. */
@Composable
internal fun ExpiringSoonLabel(expiration: Long) {
    val remaining = expiration - System.currentTimeMillis() / 1000
    if (remaining !in 0..EXPIRING_SOON_SECONDS) return
    val hoursLeft = (remaining / 3600).coerceAtLeast(1)
    Text(
        "Expires in ${hoursLeft}h — renew to keep it visible",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.error,
    )
}
