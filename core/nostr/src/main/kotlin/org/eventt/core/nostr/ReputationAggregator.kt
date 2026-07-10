package org.eventt.core.nostr

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.eventt.core.database.NostrReceiptDao

/** Thin read-side facade over [NostrReceiptDao] — the only reputation signal is mutually-receipted trade count, no ratings/reviews. */
object ReputationAggregator {
    /** Confirmed (mutual-receipt) trade count for [pubkey], across all counterparties. */
    suspend fun confirmedTradeCount(pubkey: String): Int = withContext(Dispatchers.IO) { NostrReceiptDao.countConfirmedTrades(pubkey) }
}
