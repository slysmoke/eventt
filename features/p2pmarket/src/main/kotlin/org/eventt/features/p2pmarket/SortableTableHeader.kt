package org.eventt.features.p2pmarket

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

enum class SortDirection { ASC, DESC }

/** Clickable column header for a hand-rolled table — click toggles [onClick]'s owning sort state, arrow shows current direction only when this column is the active sort. */
@Composable
internal fun SortHeaderCell(
    label: String,
    modifier: Modifier = Modifier,
    active: Boolean = false,
    direction: SortDirection = SortDirection.DESC,
    onClick: () -> Unit,
) {
    Row(
        modifier = modifier.clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
        if (active) {
            Icon(
                if (direction == SortDirection.ASC) Icons.Default.ArrowUpward else Icons.Default.ArrowDownward,
                contentDescription = null,
                modifier = Modifier.size(14.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
        }
    }
}
