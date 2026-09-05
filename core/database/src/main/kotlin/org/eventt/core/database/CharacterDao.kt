package org.eventt.core.database

import org.eventt.core.model.CharacterModel
import org.eventt.core.model.CorpFeature

object CharacterDao {
    fun insert(character: CharacterModel) {
        DatabaseManager.transaction {
            prepareStatement(
                """
                INSERT OR REPLACE INTO characters (id, name, refresh_token, access_token, token_expiry, corporation_id, corporation_name)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """.trimIndent(),
            ).use { stmt ->
                stmt.setInt(1, character.id)
                stmt.setString(2, character.name)
                stmt.setString(3, TokenCrypto.encrypt(character.refreshToken))
                stmt.setString(4, TokenCrypto.encrypt(character.accessToken))
                stmt.setLong(5, character.tokenExpiry)
                character.corporationId?.let { stmt.setInt(6, it) } ?: stmt.setNull(6, java.sql.Types.INTEGER)
                stmt.setString(7, character.corporationName)
                stmt.executeUpdate()
            }
        }
    }

    fun getAll(): List<CharacterModel> =
        DatabaseManager.transaction {
            prepareStatement("SELECT * FROM characters ORDER BY name").use { stmt ->
                stmt.executeQuery().mapResultSetToCharacters()
            }
        }

    fun getById(id: Int): CharacterModel? =
        DatabaseManager.transaction {
            prepareStatement("SELECT * FROM characters WHERE id = ?").use { stmt ->
                stmt.setInt(1, id)
                stmt.executeQuery().mapResultSetToCharacters().firstOrNull()
            }
        }

    // Locally-added characters that are members of the given corp — used to find an "acting
    // character" whose token can authorize ESI calls on the corp's behalf.
    fun getByCorporation(corporationId: Int): List<CharacterModel> =
        DatabaseManager.transaction {
            prepareStatement("SELECT * FROM characters WHERE corporation_id = ? ORDER BY name").use { stmt ->
                stmt.setInt(1, corporationId)
                stmt.executeQuery().mapResultSetToCharacters()
            }
        }

    fun updateToken(
        id: Int,
        accessToken: String,
        tokenExpiry: Long,
    ) {
        DatabaseManager.transaction {
            prepareStatement("UPDATE characters SET access_token = ?, token_expiry = ? WHERE id = ?").use { stmt ->
                stmt.setString(1, TokenCrypto.encrypt(accessToken))
                stmt.setLong(2, tokenExpiry)
                stmt.setInt(3, id)
                stmt.executeUpdate()
            }
        }
    }

    fun updateRefreshToken(
        id: Int,
        refreshToken: String,
    ) {
        DatabaseManager.transaction {
            prepareStatement("UPDATE characters SET refresh_token = ? WHERE id = ?").use { stmt ->
                stmt.setString(1, TokenCrypto.encrypt(refreshToken))
                stmt.setInt(2, id)
                stmt.executeUpdate()
            }
        }
    }

    // Also drops the character's corporation row once no other locally-added character
    // belongs to it — otherwise it lingers in `corporations` forever with nothing referencing it.
    fun delete(id: Int) {
        DatabaseManager.transaction {
            val corporationId =
                prepareStatement("SELECT corporation_id FROM characters WHERE id = ?").use { stmt ->
                    stmt.setInt(1, id)
                    stmt.executeQuery().use { rs ->
                        if (rs.next()) rs.getInt("corporation_id").takeIf { !rs.wasNull() } else null
                    }
                }
            prepareStatement("DELETE FROM characters WHERE id = ?").use { stmt ->
                stmt.setInt(1, id)
                stmt.executeUpdate()
            }
            if (corporationId != null) {
                prepareStatement(
                    """
                    DELETE FROM corporations WHERE id = ? AND id NOT IN
                        (SELECT corporation_id FROM characters WHERE corporation_id IS NOT NULL)
                    """.trimIndent(),
                ).use { stmt ->
                    stmt.setInt(1, corporationId)
                    stmt.executeUpdate()
                }
            }
        }
    }

    fun getTokenExpiry(id: Int): Long =
        DatabaseManager.transaction {
            prepareStatement("SELECT token_expiry FROM characters WHERE id = ?").use { stmt ->
                stmt.setInt(1, id)
                stmt.executeQuery().use { rs ->
                    if (rs.next()) rs.getLong("token_expiry") else 0
                }
            }
        }

    fun getDeniedCorpFeatures(characterId: Int): Set<CorpFeature> =
        DatabaseManager
            .transaction {
                prepareStatement("SELECT corp_access_denied FROM characters WHERE id = ?").use { stmt ->
                    stmt.setInt(1, characterId)
                    stmt.executeQuery().use { rs -> if (rs.next()) rs.getString("corp_access_denied") else null }
                }
            }?.split(",")
            ?.mapNotNull { runCatching { CorpFeature.valueOf(it) }.getOrNull() }
            ?.toSet()
            ?: emptySet()

    fun setCorpFeatureDenied(
        characterId: Int,
        feature: CorpFeature,
        denied: Boolean,
    ) {
        val updated = getDeniedCorpFeatures(characterId).let { if (denied) it + feature else it - feature }
        DatabaseManager.transaction {
            prepareStatement("UPDATE characters SET corp_access_denied = ? WHERE id = ?").use { stmt ->
                stmt.setString(1, updated.joinToString(",") { it.name })
                stmt.setInt(2, characterId)
                stmt.executeUpdate()
            }
        }
    }

    fun getAccessToken(id: Int): String? =
        DatabaseManager.transaction {
            prepareStatement("SELECT access_token FROM characters WHERE id = ?").use { stmt ->
                stmt.setInt(1, id)
                stmt.executeQuery().use { rs ->
                    if (rs.next()) rs.getString("access_token")?.let { TokenCrypto.decrypt(it) } else null
                }
            }
        }

    private fun java.sql.ResultSet.mapResultSetToCharacters(): List<CharacterModel> {
        val list = mutableListOf<CharacterModel>()
        while (next()) {
            list.add(
                CharacterModel(
                    id = getInt("id"),
                    name = getString("name"),
                    // Empty/undecryptable tokens (lost key, pre-encryption data) fail the next
                    // ESI call and fall through to the normal re-auth path rather than crashing.
                    refreshToken = getString("refresh_token")?.let { TokenCrypto.decrypt(it) } ?: "",
                    accessToken = getString("access_token")?.let { TokenCrypto.decrypt(it) } ?: "",
                    tokenExpiry = getLong("token_expiry"),
                    corporationId = getInt("corporation_id").takeIf { it != 0 },
                    corporationName = getString("corporation_name"),
                ),
            )
        }
        return list
    }
}

object CorporationDao {
    fun insert(
        id: Int,
        name: String,
        ticker: String,
        allianceId: Int?,
    ) {
        DatabaseManager.transaction {
            prepareStatement(
                """
                INSERT OR REPLACE INTO corporations (id, name, ticker, alliance_id)
                VALUES (?, ?, ?, ?)
                """.trimIndent(),
            ).use { stmt ->
                stmt.setInt(1, id)
                stmt.setString(2, name)
                stmt.setString(3, ticker)
                allianceId?.let { stmt.setInt(4, it) } ?: stmt.setNull(4, java.sql.Types.INTEGER)
                stmt.executeUpdate()
            }
        }
    }

    fun getAll(): List<Map<String, Any?>> =
        DatabaseManager.transaction {
            prepareStatement("SELECT * FROM corporations ORDER BY name").use { stmt ->
                val result = mutableListOf<Map<String, Any?>>()
                stmt.executeQuery().use { rs ->
                    while (rs.next()) {
                        result.add(
                            mapOf(
                                "id" to rs.getInt("id"),
                                "name" to rs.getString("name"),
                                "ticker" to rs.getString("ticker"),
                                "alliance_id" to rs.getInt("alliance_id").takeIf { it != 0 },
                            ),
                        )
                    }
                }
                result
            }
        }

    fun delete(id: Int) {
        DatabaseManager.transaction {
            prepareStatement("DELETE FROM corporations WHERE id = ?").use { stmt ->
                stmt.setInt(1, id)
                stmt.executeUpdate()
            }
        }
    }

    // ─── Tracking ─────────────────────────────────────────────────────────
    // A corp is only a selectable context / swept by background sync (wallet, orders, contracts)
    // once explicitly tracked here -- see tracked_corporations in DatabaseManager. Being a member's
    // employer (characters.corporation_id) merely makes a corp *eligible* to track.

    fun track(corporationId: Int) {
        DatabaseManager.transaction {
            prepareStatement("INSERT OR IGNORE INTO tracked_corporations (corporation_id) VALUES (?)").use { stmt ->
                stmt.setInt(1, corporationId)
                stmt.executeUpdate()
            }
        }
    }

    fun untrack(corporationId: Int) {
        DatabaseManager.transaction {
            prepareStatement("DELETE FROM tracked_corporations WHERE corporation_id = ?").use { stmt ->
                stmt.setInt(1, corporationId)
                stmt.executeUpdate()
            }
        }
    }

    fun getTrackedIds(): Set<Int> =
        DatabaseManager.transaction {
            prepareStatement("SELECT corporation_id FROM tracked_corporations").use { stmt ->
                stmt.executeQuery().use { rs ->
                    val result = mutableSetOf<Int>()
                    while (rs.next()) result += rs.getInt("corporation_id")
                    result
                }
            }
        }

    // Every tracked corp a given character list can act for, one arbitrary member each -- the
    // shared "which corps does background sync need to sweep" query for
    // WalletSyncService/ActiveOrdersSyncService/ContractWatchService, all three of which used to
    // derive this from character membership alone (see #21/#22).
    fun actingPairsForTracked(characters: List<CharacterModel>): List<Pair<Int, Int>> {
        val tracked = getTrackedIds()
        return characters
            .mapNotNull { it.corporationId }
            .distinct()
            .filter { it in tracked }
            .map { corpId -> corpId to characters.first { it.corporationId == corpId }.id }
    }
}
