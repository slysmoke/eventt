package org.eventt.core.database

data class NostrIdentityModel(
    val pubkey: String,
    val encryptedPrivkey: String,
    val label: String,
    val isActive: Boolean,
    val characterId: Int?,
)

object NostrIdentityDao {
    fun getAll(): List<NostrIdentityModel> =
        DatabaseManager.transaction {
            prepareStatement(
                "SELECT pubkey, encrypted_privkey, label, is_active, character_id FROM nostr_identity ORDER BY created_at",
            ).use { stmt ->
                stmt.executeQuery().use { rs -> rs.toModelList() }
            }
        }

    fun getActive(): NostrIdentityModel? = getAll().find { it.isActive }

    fun getByCharacterId(characterId: Int): NostrIdentityModel? =
        DatabaseManager.transaction {
            prepareStatement(
                "SELECT pubkey, encrypted_privkey, label, is_active, character_id FROM nostr_identity WHERE character_id = ?",
            ).use { stmt ->
                stmt.setInt(1, characterId)
                stmt.executeQuery().use { rs -> rs.toModelList().firstOrNull() }
            }
        }

    fun insert(
        pubkey: String,
        encryptedPrivkey: String,
        label: String,
        characterId: Int?,
    ) {
        DatabaseManager.transaction {
            prepareStatement(
                "INSERT OR REPLACE INTO nostr_identity (pubkey, encrypted_privkey, label, is_active, character_id) VALUES (?, ?, ?, 0, ?)",
            ).use { stmt ->
                stmt.setString(1, pubkey)
                stmt.setString(2, encryptedPrivkey)
                stmt.setString(3, label)
                characterId?.let { stmt.setInt(4, it) } ?: stmt.setNull(4, java.sql.Types.INTEGER)
                stmt.executeUpdate()
            }
        }
    }

    /** Exactly one identity is active at a time — clears every other row's flag first. */
    fun setActive(pubkey: String) {
        DatabaseManager.transaction {
            prepareStatement("UPDATE nostr_identity SET is_active = 0").use { it.executeUpdate() }
            prepareStatement("UPDATE nostr_identity SET is_active = 1 WHERE pubkey = ?").use { stmt ->
                stmt.setString(1, pubkey)
                stmt.executeUpdate()
            }
        }
    }

    fun delete(pubkey: String) {
        DatabaseManager.transaction {
            prepareStatement("DELETE FROM nostr_identity WHERE pubkey = ?").use { stmt ->
                stmt.setString(1, pubkey)
                stmt.executeUpdate()
            }
        }
    }

    /** Used when swapping in an imported key for a character — a character has at most one identity. */
    fun deleteByCharacterId(characterId: Int) {
        DatabaseManager.transaction {
            prepareStatement("DELETE FROM nostr_identity WHERE character_id = ?").use { stmt ->
                stmt.setInt(1, characterId)
                stmt.executeUpdate()
            }
        }
    }

    private fun java.sql.ResultSet.toModelList(): List<NostrIdentityModel> {
        val result = mutableListOf<NostrIdentityModel>()
        while (next()) {
            result.add(
                NostrIdentityModel(
                    pubkey = getString("pubkey"),
                    encryptedPrivkey = getString("encrypted_privkey"),
                    label = getString("label") ?: "",
                    isActive = getInt("is_active") == 1,
                    characterId = getInt("character_id").takeIf { !wasNull() },
                ),
            )
        }
        return result
    }
}
