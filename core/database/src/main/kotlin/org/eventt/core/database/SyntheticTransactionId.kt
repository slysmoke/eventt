package org.eventt.core.database

import java.security.MessageDigest

/**
 * Deterministic negative id for a synthetic (non-ESI) wallet transaction — negative so it can
 * never collide with a real (always-positive) ESI transaction_id, and deterministic so
 * re-processing the same source event (a P2P receipt, a failed-contract write-off) upserts the
 * same row via [WalletDao.insertTransaction]'s INSERT OR REPLACE instead of duplicating it.
 * [WalletDao.RawTxRecord.isP2p] treats every negative-id row alike: none of them are a real
 * market order, so none should carry sales tax or a broker fee.
 */
fun syntheticTransactionId(vararg parts: Any): Long {
    val digest = MessageDigest.getInstance("SHA-256").digest(parts.joinToString(":").toByteArray())
    var id = 0L
    for (i in 0 until 8) id = (id shl 8) or (digest[i].toLong() and 0xFF)
    return id or Long.MIN_VALUE
}
