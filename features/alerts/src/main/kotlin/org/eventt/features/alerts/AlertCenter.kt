package org.eventt.features.alerts

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.eventt.alerts.generated.resources.*
import org.eventt.core.database.AppState
import org.eventt.core.database.StaticDataDao
import org.eventt.core.database.ViewContext
import org.eventt.core.model.ALERT_CATEGORY_INVESTMENT
import org.eventt.core.model.PriceAlertModel
import org.eventt.features.market.ItemDetailDialog
import org.eventt.ui.common.TypeIcon
import org.eventt.ui.common.formatPriceAbbr
import org.eventt.ui.theme.negativeColor
import org.eventt.ui.theme.positiveColor
import org.jetbrains.compose.resources.stringResource

/** The item chart/book/position dialog for an alert's item, as the selected character or corp. */
@Composable
internal fun AlertChartDialog(
    typeId: Int,
    typeName: String,
    regionId: Int,
    onDismiss: () -> Unit,
) {
    val ctx by AppState.selectedContext.collectAsState()
    val regionName by produceState("", regionId) {
        value = withContext(Dispatchers.IO) { StaticDataDao.getRegionById(regionId)?.name ?: "" }
    }
    ItemDetailDialog(
        typeId = typeId,
        typeName = typeName,
        primaryRegionId = regionId,
        primaryRegionName = regionName,
        primaryStationId = null,
        charId = ctx?.actingCharId,
        corporationId = (ctx as? ViewContext.Corporation)?.corporationId,
        onDismiss = onDismiss,
    )
}

/**
 * Top-bar bell: badge with how many alerts fired since they were last cleared, opening a panel
 * that lists them (click one for its chart) with Clear all. Replaces the stacked per-alert banners,
 * which pushed the whole UI down once a bulk ladder of alerts started firing.
 */
@Composable
fun AlertCenterButton() {
    val triggered by AlertMonitor.triggered.collectAsState()
    var open by remember { mutableStateOf(false) }
    var chartFor by remember { mutableStateOf<PriceAlertModel?>(null) }

    IconButton(onClick = { open = true }) {
        BadgedBox(badge = { if (triggered.isNotEmpty()) Badge { Text("${triggered.size}") } }) {
            Icon(
                if (triggered.isNotEmpty()) Icons.Default.NotificationsActive else Icons.Default.Notifications,
                contentDescription = stringResource(Res.string.triggered_alerts),
                tint = if (triggered.isNotEmpty()) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            )
        }
    }

    if (open) {
        AlertDialog(
            onDismissRequest = { open = false },
            modifier = Modifier.widthIn(min = 560.dp, max = 720.dp),
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(Res.string.triggered_alerts))
                    Spacer(Modifier.width(8.dp))
                    Text("${triggered.size}", style = MaterialTheme.typography.labelMedium, color = Color.Gray)
                }
            },
            text = {
                if (triggered.isEmpty()) {
                    Text(stringResource(Res.string.alert_center_empty), style = MaterialTheme.typography.bodySmall)
                } else {
                    LazyColumn(modifier = Modifier.heightIn(max = 460.dp)) {
                        items(triggered.sortedByDescending { it.triggeredAt ?: 0L }, key = { it.id }) { a ->
                            TriggeredRow(a, onOpen = { chartFor = a }, onDismiss = { AlertMonitor.dismiss(a) })
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { open = false }) { Text(stringResource(Res.string.close)) } },
            dismissButton = {
                TextButton(onClick = { AlertMonitor.dismissAll() }, enabled = triggered.isNotEmpty()) {
                    Text(stringResource(Res.string.clear_all), color = MaterialTheme.colorScheme.error)
                }
            },
        )
    }

    chartFor?.let { a ->
        AlertChartDialog(a.typeId, a.typeName, AlertMonitor.effectiveRegionOf(a), onDismiss = { chartFor = null })
    }
}

@Composable
private fun TriggeredRow(
    a: PriceAlertModel,
    onOpen: () -> Unit,
    onDismiss: () -> Unit,
) {
    val above = a.condition == "above"
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onOpen).padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            if (above) Icons.Default.ArrowUpward else Icons.Default.ArrowDownward,
            null,
            Modifier.size(16.dp),
            tint = if (above) positiveColor else negativeColor,
        )
        Spacer(Modifier.width(8.dp))
        TypeIcon(a.typeId, size = 28.dp)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                a.typeName,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                stringResource(
                    if (above) Res.string.alert_rose_to else Res.string.alert_fell_to,
                    stringResource(if (a.orderType == "buy") Res.string.bid else Res.string.ask),
                    formatPriceAbbr(a.targetPrice),
                ) +
                    (a.triggeredAt?.let { " · " + formatDateTime(it) } ?: ""),
                style = MaterialTheme.typography.labelSmall,
                color = Color.Gray,
            )
        }
        Text(
            if (a.category == ALERT_CATEGORY_INVESTMENT) stringResource(Res.string.long_term) else stringResource(Res.string.general),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
        )
        IconButton(onClick = onDismiss, modifier = Modifier.size(28.dp)) {
            Icon(Icons.Default.Close, stringResource(Res.string.dismiss), Modifier.size(14.dp))
        }
    }
    HorizontalDivider(thickness = 0.5.dp)
}
