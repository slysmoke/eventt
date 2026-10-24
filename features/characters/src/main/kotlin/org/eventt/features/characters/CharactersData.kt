package org.eventt.features.characters

import org.eventt.core.database.CharacterDao
import org.eventt.core.database.CorporationDao
import org.eventt.core.database.DatabaseManager
import org.eventt.core.database.StaticDataDao
import org.eventt.core.model.CharacterModel
import org.eventt.core.model.CorpFeature
import java.time.LocalDate

/** A character row: the account plus what it's doing in the market, from local data only. */
internal data class CharRow(
    val c: CharacterModel,
    val tokenValid: Boolean,
    val sellOrders: Int,
    val buyOrders: Int,
    // Corp orders this character placed (they live in the corp's list, not the character's).
    val corpOrders: Int,
    val trades30d: Int,
    val lastTrade: String?,
    val salesTaxPct: Double,
    val brokerFeePct: Double,
    val deniedCorp: Set<CorpFeature>,
)

internal data class CorpRow(
    val id: Int,
    val name: String,
    val ticker: String,
    val tracked: Boolean,
    val members: List<String>,
    val orders: Int,
    val trades30d: Int,
)

private fun count(
    sql: String,
    vararg args: Any,
): Int =
    DatabaseManager.transaction {
        prepareStatement(sql).use { st ->
            args.forEachIndexed { i, a -> st.setObject(i + 1, a) }
            st.executeQuery().use { rs -> if (rs.next()) rs.getInt(1) else 0 }
        }
    }

private fun lastTrade(characterId: Int): String? =
    DatabaseManager.transaction {
        prepareStatement("SELECT MAX(date) FROM transactions WHERE character_id = ?").use { st ->
            st.setInt(1, characterId)
            st.executeQuery().use { rs -> if (rs.next()) rs.getString(1) else null }
        }
    }

internal fun loadRows(
    now: Long = System.currentTimeMillis(),
    today: LocalDate = LocalDate.now(),
): Pair<List<CharRow>, List<CorpRow>> {
    val since = today.minusDays(29).toString()
    val chars = CharacterDao.getAll()
    val tracked = CorporationDao.getTrackedIds()
    val charRows =
        chars.map { c ->
            CharRow(
                c = c,
                tokenValid = c.tokenExpiry > now,
                sellOrders =
                    count(
                        "SELECT COUNT(*) FROM active_orders WHERE character_id = ? AND corporation_id IS NULL AND is_buy_order = 0",
                        c.id,
                    ),
                buyOrders =
                    count(
                        "SELECT COUNT(*) FROM active_orders WHERE character_id = ? AND corporation_id IS NULL AND is_buy_order = 1",
                        c.id,
                    ),
                corpOrders =
                    count(
                        "SELECT COUNT(*) FROM active_orders WHERE character_id = ? AND corporation_id IS NOT NULL",
                        c.id,
                    ),
                trades30d = count("SELECT COUNT(*) FROM transactions WHERE character_id = ? AND date >= ?", c.id, since),
                lastTrade = lastTrade(c.id),
                salesTaxPct = StaticDataDao.getCharSalesTax(c.id),
                brokerFeePct = StaticDataDao.getCharBrokersFee(c.id),
                deniedCorp = CharacterDao.getDeniedCorpFeatures(c.id),
            )
        }
    val corpRows =
        CorporationDao.getAll().mapNotNull { m ->
            val id = m["id"] as? Int ?: return@mapNotNull null
            CorpRow(
                id = id,
                name = m["name"]?.toString().orEmpty(),
                ticker = m["ticker"]?.toString().orEmpty(),
                tracked = id in tracked,
                members = chars.filter { it.corporationId == id }.map { it.name },
                orders = count("SELECT COUNT(*) FROM active_orders WHERE corporation_id = ?", id),
                trades30d = count("SELECT COUNT(*) FROM transactions WHERE corporation_id = ? AND date >= ?", id, since),
            )
        }
    return charRows to corpRows
}
