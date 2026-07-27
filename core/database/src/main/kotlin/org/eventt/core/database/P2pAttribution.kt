package org.eventt.core.database

// Which character or corporation a P2P trade's synthetic wallet transaction (see
// core:nostr's ReceiptService) is booked against for cost-basis purposes — independent of
// which Nostr identity/character actually negotiated the trade, since a character can be
// selling on behalf of their corp. Always editable afterward (core:nostr's
// ReceiptService.bookCostBasisEntry), this is only ever a label on top of the existing
// transactions.character_id/corporation_id columns.
sealed class TransactionAttribution {
    data class Character(
        val characterId: Int,
    ) : TransactionAttribution()

    data class Corporation(
        val corporationId: Int,
    ) : TransactionAttribution()

    fun encode(): String =
        when (this) {
            is Character -> "char:$characterId"
            is Corporation -> "corp:$corporationId"
        }

    companion object {
        fun decode(saved: String?): TransactionAttribution? {
            if (saved.isNullOrEmpty()) return null
            val parts = saved.split(":")
            val id = parts.getOrNull(1)?.toIntOrNull() ?: return null
            return when (parts.getOrNull(0)) {
                "char" -> Character(id)
                "corp" -> Corporation(id)
                else -> null
            }
        }
    }
}

/**
 * Default booking target for newly-completed P2P trades — what a trade starts out attributed
 * to, not a permanent assignment. core:nostr's ReceiptService.bookCostBasisEntry reassigns any
 * single trade afterward independent of this.
 */
object P2pAttributionDefault {
    private const val SETTING_KEY = "p2p.default_attribution"

    fun get(): TransactionAttribution? = TransactionAttribution.decode(StaticDataDao.getSetting(SETTING_KEY))

    fun set(attribution: TransactionAttribution?) {
        StaticDataDao.setSetting(SETTING_KEY, attribution?.encode() ?: "")
    }
}
