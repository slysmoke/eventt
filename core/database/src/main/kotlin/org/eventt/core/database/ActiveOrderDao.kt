package org.eventt.core.database

import java.sql.Types

/**
 * Local cache of a character's/corp's currently active orders — lets the Orders screen show the
 * last-known snapshot instantly on startup instead of an empty table while ESI's live fetch is
 * in flight, and survives an app restart for data that arrived via a Marketlogs file import.
 * Unlike [OrderHistoryDao] (append-only), this is a full-snapshot replace on every refresh: an
 * order that's no longer present in the latest fetch (filled/cancelled/expired) must vanish here
 * too, not just accumulate.
 */
object ActiveOrderDao {
    data class ActiveOrderRecord(
        val orderId: Long,
        val typeId: Int,
        val typeName: String,
        val locationId: Long,
        val regionId: Int,
        val stationName: String,
        val price: Double,
        val volumeTotal: Int,
        val volumeRemaining: Int,
        val isBuyOrder: Boolean,
        val duration: Int,
        val issued: String,
        val state: String,
        val issuedByCharId: Int?,
        val characterId: Int?,
        val corporationId: Int?,
        // Self-computed relist tracking: the wallet journal has no order_id to link a brokers_fee
        // entry back to a specific order (and only refreshes hourly), so these are accumulated by
        // diffing this order's price against its last-seen price on every refresh instead.
        val relistCount: Int = 0,
        val relistFeesPaid: Double = 0.0,
        // Server "as of" time (ESI Last-Modified) behind the current price/relist stats — see
        // bumpRelistStats's asOf parameter.
        val priceUpdatedAt: Long = 0,
    )

    private data class WhereClause(
        val sql: String,
        val params: List<Any?>,
    )

    // Character scope excludes corp orders that character placed (corporation_id IS NOT NULL on
    // those rows, see replaceCorpOrders) — a solo character view must never surface orders that
    // belong to a corp wallet, even though they're stored under that same character_id.
    private fun buildWhereClause(
        characterId: Int?,
        corporationId: Int?,
    ): WhereClause =
        when {
            characterId != null -> WhereClause("WHERE character_id = ? AND corporation_id IS NULL", listOf(characterId))
            corporationId != null -> WhereClause("WHERE corporation_id = ?", listOf(corporationId))
            else -> WhereClause("", emptyList())
        }

    /**
     * Replaces one character's personal active-orders snapshot (corp orders that character
     * placed are untouched — see [replaceCorpOrders]) in a single transaction.
     */
    fun replaceAll(
        characterId: Int,
        records: List<ActiveOrderRecord>,
    ) {
        DatabaseManager.transaction {
            prepareStatement("DELETE FROM active_orders WHERE character_id = ? AND corporation_id IS NULL").use { ps ->
                ps.setInt(1, characterId)
                ps.executeUpdate()
            }
            if (records.isEmpty()) return@transaction
            val sql =
                """
                INSERT OR REPLACE INTO active_orders
                (order_id, type_id, type_name, location_id, region_id, station_name, price, volume_total,
                 volume_remaining, is_buy_order, duration, issued, state, issued_by_char_id, character_id, corporation_id,
                 relist_count, relist_fees_paid, price_updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent()
            prepareStatement(sql).use { ps ->
                records.forEach { r ->
                    ps.setLong(1, r.orderId)
                    ps.setInt(2, r.typeId)
                    ps.setString(3, r.typeName)
                    ps.setLong(4, r.locationId)
                    ps.setInt(5, r.regionId)
                    ps.setString(6, r.stationName)
                    ps.setDouble(7, r.price)
                    ps.setInt(8, r.volumeTotal)
                    ps.setInt(9, r.volumeRemaining)
                    ps.setInt(10, if (r.isBuyOrder) 1 else 0)
                    ps.setInt(11, r.duration)
                    ps.setString(12, r.issued)
                    ps.setString(13, r.state)
                    if (r.issuedByCharId != null) ps.setInt(14, r.issuedByCharId) else ps.setNull(14, Types.INTEGER)
                    if (r.characterId != null) ps.setInt(15, r.characterId) else ps.setNull(15, Types.INTEGER)
                    if (r.corporationId != null) ps.setInt(16, r.corporationId) else ps.setNull(16, Types.INTEGER)
                    ps.setInt(17, r.relistCount)
                    ps.setDouble(18, r.relistFeesPaid)
                    ps.setLong(19, r.priceUpdatedAt)
                    ps.addBatch()
                }
                ps.executeBatch()
            }
        }
    }

    // Corp orders are stored under the placing character's character_id (not a flat corporation-
    // wide bucket) so relist history stays put regardless of which corp-mate's token is used to
    // view them. Deleting/replacing per placer — instead of the whole corp scope at once — means
    // a fetch that happens not to include every member this time (a role check, a transient ESI
    // hiccup) never wipes another member's relist history out from under them.
    // ponytail: a member who drops from "has open corp orders" to zero leaves a stale cached row
    // behind (nobody to key the delete off) until their next non-empty refresh cleans it up —
    // acceptable staleness, upgrade to a known-members delete list if that turns out to matter.
    fun replaceCorpOrders(
        corporationId: Int,
        records: List<ActiveOrderRecord>,
    ) {
        val placers = records.mapNotNull { it.characterId }.toSet()
        if (placers.isEmpty()) return
        DatabaseManager.transaction {
            prepareStatement("DELETE FROM active_orders WHERE corporation_id = ? AND character_id = ?").use { ps ->
                placers.forEach { placer ->
                    ps.setInt(1, corporationId)
                    ps.setInt(2, placer)
                    ps.addBatch()
                }
                ps.executeBatch()
            }
            val sql =
                """
                INSERT OR REPLACE INTO active_orders
                (order_id, type_id, type_name, location_id, region_id, station_name, price, volume_total,
                 volume_remaining, is_buy_order, duration, issued, state, issued_by_char_id, character_id, corporation_id,
                 relist_count, relist_fees_paid, price_updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent()
            prepareStatement(sql).use { ps ->
                records.forEach { r ->
                    ps.setLong(1, r.orderId)
                    ps.setInt(2, r.typeId)
                    ps.setString(3, r.typeName)
                    ps.setLong(4, r.locationId)
                    ps.setInt(5, r.regionId)
                    ps.setString(6, r.stationName)
                    ps.setDouble(7, r.price)
                    ps.setInt(8, r.volumeTotal)
                    ps.setInt(9, r.volumeRemaining)
                    ps.setInt(10, if (r.isBuyOrder) 1 else 0)
                    ps.setInt(11, r.duration)
                    ps.setString(12, r.issued)
                    ps.setString(13, r.state)
                    if (r.issuedByCharId != null) ps.setInt(14, r.issuedByCharId) else ps.setNull(14, Types.INTEGER)
                    if (r.characterId != null) ps.setInt(15, r.characterId) else ps.setNull(15, Types.INTEGER)
                    ps.setInt(16, corporationId)
                    ps.setInt(17, r.relistCount)
                    ps.setDouble(18, r.relistFeesPaid)
                    ps.setLong(19, r.priceUpdatedAt)
                    ps.addBatch()
                }
                ps.executeBatch()
            }
        }
    }

    // Incremental single-row update for a relist detected between full snapshots (e.g. from the
    // higher-frequency public order book poll) -- avoids waiting for the next full replaceAll to
    // record it. No-ops if the order isn't in the current snapshot (already filled/cancelled).
    // order_id is the table's primary key, so it alone identifies the row; characterId (the
    // order's owner — the character itself for personal orders, the placer for corp orders) is
    // just a defense-in-depth scope check, not load-bearing for uniqueness.
    // Two guards against a false relist: `price != ?` rejects the same already-applied transition
    // reported twice; `price_updated_at < ?` rejects a transition whose own asOf (the ESI
    // response's Last-Modified this was detected from) is older than the last-applied one —
    // i.e. this call is looking at a snapshot that's actually stale relative to what we already
    // know, not a genuinely newer relist.
    fun bumpRelistStats(
        orderId: Long,
        characterId: Int,
        newPrice: Double,
        addedFee: Double,
        asOf: Long,
    ) {
        DatabaseManager.transaction {
            prepareStatement(
                "UPDATE active_orders SET price = ?, relist_count = relist_count + 1, " +
                    "relist_fees_paid = relist_fees_paid + ?, price_updated_at = ? " +
                    "WHERE order_id = ? AND character_id = ? AND price != ? AND price_updated_at < ?",
            ).use { ps ->
                ps.setDouble(1, newPrice)
                ps.setDouble(2, addedFee)
                ps.setLong(3, asOf)
                ps.setLong(4, orderId)
                ps.setInt(5, characterId)
                ps.setDouble(6, newPrice)
                ps.setLong(7, asOf)
                ps.executeUpdate()
            }
        }
    }

    fun getAll(
        characterId: Int? = null,
        corporationId: Int? = null,
    ): List<ActiveOrderRecord> {
        val where = buildWhereClause(characterId, corporationId)
        return DatabaseManager.transaction {
            prepareStatement("SELECT * FROM active_orders ${where.sql} ORDER BY issued DESC").use { ps ->
                where.params.forEachIndexed { i, p -> ps.setObject(i + 1, p) }
                val rs = ps.executeQuery()
                val result = mutableListOf<ActiveOrderRecord>()
                while (rs.next()) {
                    result.add(
                        ActiveOrderRecord(
                            orderId = rs.getLong("order_id"),
                            typeId = rs.getInt("type_id"),
                            typeName = rs.getString("type_name") ?: "",
                            locationId = rs.getLong("location_id"),
                            regionId = rs.getInt("region_id"),
                            stationName = rs.getString("station_name") ?: "",
                            price = rs.getDouble("price"),
                            volumeTotal = rs.getInt("volume_total"),
                            volumeRemaining = rs.getInt("volume_remaining"),
                            isBuyOrder = rs.getInt("is_buy_order") == 1,
                            duration = rs.getInt("duration"),
                            issued = rs.getString("issued") ?: "",
                            state = rs.getString("state") ?: "active",
                            issuedByCharId = rs.getInt("issued_by_char_id").takeIf { !rs.wasNull() },
                            characterId = rs.getInt("character_id").takeIf { !rs.wasNull() },
                            corporationId = rs.getInt("corporation_id").takeIf { !rs.wasNull() },
                            relistCount = rs.getInt("relist_count"),
                            relistFeesPaid = rs.getDouble("relist_fees_paid"),
                            priceUpdatedAt = rs.getLong("price_updated_at"),
                        ),
                    )
                }
                result
            }
        }
    }
}
