package org.eventt.features.orders

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.eventt.ui.common.formatIsk

@Composable
internal fun FailedContractWriteOffDialog(
    lines: List<FailedContractLine>,
    onWriteOff: (FailedContractLine) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Lost to failed courier contracts") },
        text = {
            Column {
                Text(
                    "These courier contracts failed (the ship carrying them was destroyed) — write off the cargo to " +
                        "correct your FIFO inventory and cost basis. Priced at the contract's own collateral (its " +
                        "value share of the total, if the contract carried more than one item), not zero — that's " +
                        "what ESI actually pays back on a failure.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(10.dp))
                HorizontalDivider()
                LazyColumn(modifier = Modifier.heightIn(max = 320.dp)) {
                    items(lines, key = { "${it.contract.contractId}:${it.typeId}" }) { line ->
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("${line.quantity}x ${line.typeName}", style = MaterialTheme.typography.bodyMedium)
                                Text(
                                    "Collateral recovery: ${formatIsk(line.unitPrice * line.quantity)}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            OutlinedButton(onClick = { onWriteOff(line) }) { Text("Write off") }
                        }
                        HorizontalDivider()
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}
