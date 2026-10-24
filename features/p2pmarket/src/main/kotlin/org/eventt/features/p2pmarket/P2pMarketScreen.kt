package org.eventt.features.p2pmarket

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.eventt.core.database.NostrOrderModel
import org.eventt.core.database.NostrRelayDao
import org.eventt.core.database.NostrRelayModel
import org.eventt.core.nostr.NostrRelayEvent
import org.eventt.core.nostr.NostrRelayManager
import org.eventt.core.nostr.OrderFilter
import org.eventt.core.nostr.OrderRepository
import org.eventt.core.nostr.PresenceService
import org.eventt.p2pmarket.generated.resources.*
import org.eventt.ui.common.*
import org.eventt.ui.theme.warningColor
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

private enum class P2pMarketTab(
    val label: StringResource,
) {
    BROWSE(Res.string.tab_browse),
    MY_ORDERS(Res.string.tab_my_orders),
    INCOMING_REQUESTS(Res.string.tab_incoming),
    MY_REQUESTS(Res.string.tab_my_requests),
    INBOX(Res.string.tab_inbox),
}

/** Small numeric pill shown next to a tab/nav label — hidden entirely when there's nothing to flag. */
@Composable
fun CountBadge(count: Int) {
    if (count <= 0) return
    Box(
        modifier =
            Modifier
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.error)
                .padding(horizontal = 6.dp, vertical = 1.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            if (count > 99) "99+" else count.toString(),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onError,
        )
    }
}

@Composable
fun P2pMarketScreen() {
    var tab by remember { mutableStateOf(P2pMarketTab.BROWSE) }
    var showGuide by remember { mutableStateOf(false) }
    val pendingIncoming = rememberReservationCount("seller", listOf("sent"))
    val awaitingCompletion = rememberReservationCount("buyer", listOf("accepted"))
    val net = rememberNetworkStats()

    if (showGuide) {
        P2pMarketGuideDialog(onDismiss = { showGuide = false })
    }

    Column(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(Res.string.p2p_title), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.weight(1f))
            Segment("? " + stringResource(Res.string.how_p2p_works), false) { showGuide = true }
        }
        TabBar(
            P2pMarketTab.entries.map { t ->
                TabItem(
                    stringResource(t.label),
                    when (t) {
                        P2pMarketTab.BROWSE -> net.networkOrders

                        P2pMarketTab.MY_ORDERS -> net.myOrders

                        // Only flag these when something waits on you.
                        P2pMarketTab.INCOMING_REQUESTS -> pendingIncoming.takeIf { it > 0 }

                        P2pMarketTab.MY_REQUESTS -> awaitingCompletion.takeIf { it > 0 }

                        P2pMarketTab.INBOX -> null
                    },
                    MaterialTheme.colorScheme.error.takeIf {
                        (t == P2pMarketTab.INCOMING_REQUESTS && pendingIncoming > 0) ||
                            (t == P2pMarketTab.MY_REQUESTS && awaitingCompletion > 0)
                    },
                )
            },
            tab.ordinal,
            { tab = P2pMarketTab.entries[it] },
        )
        Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min).border(1.dp, outline(), MaterialTheme.shapes.extraSmall)) {
            Kpi(
                stringResource(Res.string.kpi_relays),
                "${net.relaysConnected} / ${net.relaysEnabled}",
                null,
                null,
                null,
                Res.string.tip_kpi_relays,
                valueColor = MaterialTheme.colorScheme.error.takeIf { net.relaysEnabled > 0 && net.relaysConnected == 0 },
            )
            Kpi(stringResource(Res.string.kpi_online), net.tradersOnline.toString(), null, null, null, Res.string.tip_kpi_online)
            Kpi(
                stringResource(Res.string.kpi_network_orders),
                net.networkOrders.toString(),
                stringResource(Res.string.kpi_sell_buy, net.networkSells.toString(), (net.networkOrders - net.networkSells).toString()),
                null,
                null,
            )
            Kpi(stringResource(Res.string.tab_my_orders), net.myOrders.toString(), null, null, null)
            Kpi(
                stringResource(Res.string.kpi_waiting_you),
                pendingIncoming.toString(),
                null,
                null,
                null,
                Res.string.tip_kpi_waiting_you,
                valueColor = MaterialTheme.colorScheme.error.takeIf { pendingIncoming > 0 },
            )
            Kpi(
                stringResource(Res.string.kpi_to_complete),
                awaitingCompletion.toString(),
                null,
                null,
                null,
                Res.string.tip_kpi_to_complete,
                valueColor = warningColor.takeIf { awaitingCompletion > 0 },
                last = true,
            )
        }
        Box(Modifier.fillMaxWidth().weight(1f)) {
            when (tab) {
                P2pMarketTab.BROWSE -> BrowseScreen()
                P2pMarketTab.MY_ORDERS -> MyOrdersScreen()
                P2pMarketTab.INCOMING_REQUESTS -> IncomingRequestsScreen()
                P2pMarketTab.MY_REQUESTS -> MyRequestsScreen()
                P2pMarketTab.INBOX -> InboxScreen()
            }
        }
    }
}

private class NetworkStats(
    val relaysEnabled: Int = 0,
    val relaysConnected: Int = 0,
    val tradersOnline: Int = 0,
    val networkOrders: Int = 0,
    val networkSells: Int = 0,
    val myOrders: Int = 0,
)

// Relays and orders refresh on every relay event; presence is its own live flow.
@Composable
private fun rememberNetworkStats(): NetworkStats {
    var orders by remember { mutableStateOf<List<NostrOrderModel>>(emptyList()) }
    var relays by remember { mutableStateOf<List<NostrRelayModel>>(emptyList()) }
    val presence by PresenceService.presence.collectAsState()
    LaunchedEffect(Unit) { OrderRepository.browse(OrderFilter()).collect { orders = it } }
    LaunchedEffect(Unit) {
        relays = withContext(Dispatchers.IO) { NostrRelayDao.getAll() }
        NostrRelayManager.events.collect { event ->
            if (event is NostrRelayEvent.RelayStatusChanged) relays = withContext(Dispatchers.IO) { NostrRelayDao.getAll() }
        }
    }
    val nowSec = System.currentTimeMillis() / 1000
    val enabled = relays.filter { it.enabled }
    return NetworkStats(
        relaysEnabled = enabled.size,
        relaysConnected = enabled.count { it.lastStatus == "connected" },
        tradersOnline = presence.values.count { it.isOnline(nowSec) },
        networkOrders = orders.size,
        networkSells = orders.count { it.side.equals("sell", ignoreCase = true) },
        myOrders = orders.count { it.isMine },
    )
}

@Composable
private fun P2pMarketGuideDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.how_p2p_works)) },
        text = {
            Column(modifier = Modifier.widthIn(max = 420.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    stringResource(Res.string.guide_1),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    stringResource(Res.string.guide_attr_title),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    stringResource(Res.string.guide_2),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    stringResource(Res.string.guide_3),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    stringResource(Res.string.guide_4),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(Res.string.got_it)) } },
    )
}
