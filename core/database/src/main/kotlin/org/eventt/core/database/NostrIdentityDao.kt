package org.eventt.core.database

data class NostrIdentityModel(
    val pubkey: String,
    val encryptedPrivkey: String,
    val label: String,
    val isActive: Boolean,
)

object NostrIdentityDao {
    fun getAll(): List<NostrIdentityModel> =
        DatabaseManager.transaction {
            prepareStatement("SELECT pubkey, encrypted_privkey, label, is_active FROM nostr_identity ORDER BY created_at").use { stmt ->
                stmt.executeQuery().use { rs ->
                    val result = mutableListOf<NostrIdentityModel>()
                    while (rs.next()) {
                        result.add(
                            NostrIdentityModel(
                                pubkey = rs.getString("pubkey"),
                                encryptedPrivkey = rs.getString("encrypted_privkey"),
                                label = rs.getString("label") ?: "",
                                isActive = rs.getInt("is_active") == 1,
                            ),
                        )
                    }
                    result
                }
            }
        }

    fun getActive(): NostrIdentityModel? = getAll().find { it.isActive }

    fun insert(
        pubkey: String,
        encryptedPrivkey: String,
        label: String,
    ) {
        DatabaseManager.transaction {
            prepareStatement(
                "INSERT OR REPLACE INTO nostr_identity (pubkey, encrypted_privkey, label, is_active) VALUES (?, ?, ?, 0)",
            ).use { stmt ->
                stmt.setString(1, pubkey)
                stmt.setString(2, encryptedPrivkey)
                stmt.setString(3, label)
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
}
