package org.eventt.core.nostr

import com.vitorpamplona.quartz.nip01Core.core.hexToByteArray
import com.vitorpamplona.quartz.nip01Core.crypto.KeyPair
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.eventt.core.database.NostrIdentityDao
import org.eventt.core.database.NostrIdentityModel
import org.eventt.core.database.NostrKeyCrypto

data class NostrIdentity(
    val pubkey: String,
    val label: String,
    val keyPair: KeyPair,
)

/**
 * Owns the only code path in the app that ever holds a decrypted Nostr private key in memory —
 * generation/import go through [QuartzGateway], persistence through [NostrKeyCrypto] (its own
 * encryption key, separate from OAuth token storage) and [NostrIdentityDao].
 */
object NostrIdentityService {
    suspend fun generateNew(label: String): NostrIdentity =
        withContext(Dispatchers.IO) {
            persist(QuartzGateway.generateKeyPair(), label)
        }

    /** Returns null if [nsecOrHex] doesn't parse as a valid nsec1.../hex private key. */
    suspend fun importPrivateKey(
        nsecOrHex: String,
        label: String,
    ): NostrIdentity? =
        withContext(Dispatchers.IO) {
            val keyPair = QuartzGateway.importPrivateKey(nsecOrHex) ?: return@withContext null
            persist(keyPair, label)
        }

    suspend fun getActiveIdentity(): NostrIdentity? =
        withContext(Dispatchers.IO) {
            NostrIdentityDao.getActive()?.let { rowToIdentity(it) }
        }

    suspend fun listIdentities(): List<NostrIdentity> =
        withContext(Dispatchers.IO) {
            NostrIdentityDao.getAll().map { rowToIdentity(it) }
        }

    suspend fun switchActive(pubkey: String) {
        withContext(Dispatchers.IO) { NostrIdentityDao.setActive(pubkey) }
    }

    suspend fun delete(pubkey: String) {
        withContext(Dispatchers.IO) { NostrIdentityDao.delete(pubkey) }
    }

    private fun persist(
        keyPair: KeyPair,
        label: String,
    ): NostrIdentity {
        val pubkeyHex = QuartzGateway.pubKeyHex(keyPair)
        val privKeyHex = requireNotNull(QuartzGateway.privKeyHex(keyPair)) { "generated/imported keypair unexpectedly read-only" }
        NostrIdentityDao.insert(pubkeyHex, NostrKeyCrypto.encrypt(privKeyHex), label)
        NostrIdentityDao.setActive(pubkeyHex)
        return NostrIdentity(pubkeyHex, label, keyPair)
    }

    private fun rowToIdentity(row: NostrIdentityModel): NostrIdentity {
        val privKeyHex = requireNotNull(NostrKeyCrypto.decrypt(row.encryptedPrivkey)) { "corrupt or undecryptable identity key" }
        return NostrIdentity(row.pubkey, row.label, KeyPair(privKey = privKeyHex.hexToByteArray()))
    }
}
