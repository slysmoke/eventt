package org.eventt.core.database

import org.eventt.core.model.CharacterModel

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

    fun delete(id: Int) {
        DatabaseManager.transaction {
            prepareStatement("DELETE FROM characters WHERE id = ?").use { stmt ->
                stmt.setInt(1, id)
                stmt.executeUpdate()
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
}
