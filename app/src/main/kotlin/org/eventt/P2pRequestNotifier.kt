package org.eventt

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.launch
import org.eventt.app.generated.resources.*
import org.eventt.core.database.NostrReservationModel
import org.eventt.core.nostr.NostrIdentityService
import org.eventt.core.nostr.NostrRelayEvent
import org.eventt.core.nostr.NostrRelayManager
import org.eventt.notify.TrayNotifier
import org.jetbrains.compose.resources.getPluralString
import org.jetbrains.compose.resources.getString

private const val COALESCE_WINDOW_MILLIS = 5_000L

/**
 * Tray notifications for incoming P2P buy requests — including ones addressed to a character
 * other than the currently active one (the DM subscription covers every local identity).
 * Requests are coalesced: the first arrival opens a [COALESCE_WINDOW_MILLIS] window and
 * everything that lands inside it becomes ONE popup ("N new requests") — so a burst, like the
 * backlog of requests that arrived while the app was closed, can't machine-gun the tray.
 * Duplicate re-deliveries never reach here at all ([NostrRelayEvent.IncomingBuyRequest] only
 * fires for requests newly inserted into the local DB).
 */
object P2pRequestNotifier {
    private var scope: CoroutineScope? = null

    fun start() {
        if (scope != null) return
        val s = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        scope = s
        val incoming = Channel<NostrReservationModel>(Channel.UNLIMITED)
        s.launch {
            NostrRelayManager.events
                .filterIsInstance<NostrRelayEvent.IncomingBuyRequest>()
                .collect { incoming.send(it.reservation) }
        }
        s.launch {
            while (true) {
                val first = incoming.receive()
                delay(COALESCE_WINDOW_MILLIS)
                val batch = mutableListOf(first)
                while (true) batch += incoming.tryReceive().getOrNull() ?: break
                notify(batch)
            }
        }
    }

    private suspend fun notify(batch: List<NostrReservationModel>) {
        val text =
            if (batch.size == 1) {
                val r = batch.first()
                val who = r.buyerChar.ifBlank { "${r.buyerPubkey.take(12)}…" }
                // Which of our characters the request is for — with every identity subscribed,
                // "from whom" alone doesn't tell a multi-character seller where to look.
                val toChar = NostrIdentityService.getIdentityByPubkey(r.sellerPubkey)?.label
                getString(Res.string.p2p_request_from, who, r.qty) + (toChar?.let { " " + getString(Res.string.p2p_request_to, it) } ?: "")
            } else {
                getPluralString(Res.plurals.p2p_new_requests, batch.size, batch.size)
            }
        TrayNotifier.notify(getString(Res.string.p2p_market_title), text)
    }
}
