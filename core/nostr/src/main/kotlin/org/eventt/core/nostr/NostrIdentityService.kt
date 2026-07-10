package org.eventt.core.nostr

import com.vitorpamplona.quartz.nip01Core.core.hexToByteArray
import com.vitorpamplona.quartz.nip01Core.crypto.KeyPair
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.eventt.core.database.AppState
import org.eventt.core.database.CharacterDao
import org.eventt.core.database.NostrIdentityDao
import org.eventt.core.database.NostrIdentityModel
import org.eventt.core.database.NostrKeyCrypto
import org.eventt.core.model.CharacterModel

data class NostrIdentity(
    val pubkey: String,
    val label: String,
    val keyPair: KeyPair,
    val characterId: Int?,
)

/**
 * Owns the only code path in the app that ever holds a decrypted Nostr private key in memory —
 * generation/import go through [QuartzGateway], persistence through [NostrKeyCrypto] (its own
 * encryption key, separate from OAuth token storage) and [NostrIdentityDao]. Each EVE character
 * gets its own P2P Market identity (one nostr_identity row per character_id, enforced by always
 * deleting-then-inserting rather than having two rows fight over the same character) —
 * [ensureIdentitiesForAllCharacters] auto-generates any missing ones, so there's no separate
 * manual "create identity" step; import/export exist for moving a character's key between machines.
 */
object NostrIdentityService {
    /** Idempotent — a character that already has an identity is left untouched. */
    suspend fun ensureIdentityForCharacter(character: CharacterModel): NostrIdentity =
        withContext(Dispatchers.IO) {
            NostrIdentityDao.getByCharacterId(character.id)?.let { return@withContext rowToIdentity(it) }
            val identity = persist(QuartzGateway.generateKeyPair(), character.name, character.id)
            if (NostrIdentityDao.getActive() == null) NostrIdentityDao.setActive(identity.pubkey)
            identity
        }

    /** Called from Settings whenever the identity list is shown — backfills identities for any newly-added character. */
    suspend fun ensureIdentitiesForAllCharacters(): List<NostrIdentity> =
        withContext(Dispatchers.IO) {
            CharacterDao.getAll().map { ensureIdentityForCharacter(it) }
        }

    /** Replaces [character]'s current identity (if any) with an imported key. Null if [nsecOrHex] doesn't parse. */
    suspend fun importPrivateKeyForCharacter(
        nsecOrHex: String,
        character: CharacterModel,
    ): NostrIdentity? =
        withContext(Dispatchers.IO) {
            val keyPair = QuartzGateway.importPrivateKey(nsecOrHex) ?: return@withContext null
            val wasActive = NostrIdentityDao.getByCharacterId(character.id)?.isActive ?: false
            NostrIdentityDao.deleteByCharacterId(character.id)
            val identity = persist(keyPair, character.name, character.id)
            if (wasActive || NostrIdentityDao.getActive() == null) NostrIdentityDao.setActive(identity.pubkey)
            identity
        }

    /** Null if [pubkey] doesn't match a stored identity. */
    suspend fun exportPrivateKey(pubkey: String): String? =
        withContext(Dispatchers.IO) {
            val row = NostrIdentityDao.getAll().find { it.pubkey == pubkey } ?: return@withContext null
            val privKeyHex = requireNotNull(NostrKeyCrypto.decrypt(row.encryptedPrivkey)) { "corrupt or undecryptable identity key" }
            QuartzGateway.encodeAsNsec(KeyPair(privKey = privKeyHex.hexToByteArray()))
        }

    suspend fun getActiveIdentity(): NostrIdentity? =
        withContext(Dispatchers.IO) {
            NostrIdentityDao.getActive()?.let { rowToIdentity(it) }
        }

    /**
     * Looks up whichever of the user's identities owns [pubkey] — the app holds every identity's
     * private key regardless of which one is currently "active," so actions on an *existing*
     * order/reservation (renew, cancel, accept, decline, release, mark-completed) should resolve
     * the identity that actually owns it rather than requiring the user to first switch their
     * active character to match. Only [getActiveIdentity] should be used for *new* orders/requests,
     * where there's no prior owner to resolve against.
     */
    suspend fun getIdentityByPubkey(pubkey: String): NostrIdentity? =
        withContext(Dispatchers.IO) {
            NostrIdentityDao.getAll().find { it.pubkey == pubkey }?.let { rowToIdentity(it) }
        }

    suspend fun listIdentities(): List<NostrIdentity> =
        withContext(Dispatchers.IO) {
            NostrIdentityDao.getAll().map { rowToIdentity(it) }
        }

    suspend fun switchActive(pubkey: String) {
        withContext(Dispatchers.IO) { NostrIdentityDao.setActive(pubkey) }
    }

    /**
     * Keeps the active P2P Market identity in lockstep with whichever character the app's main nav
     * has selected — including the acting character behind a corporation selection, since
     * [AppState.selectedCharId] already resolves that. There's no manual "which character trades
     * P2P" choice anymore (see [NostrIdentityCard][org.eventt.features.settings] in Settings,
     * which is now read-only); this is the one place that decides it. Never returns — collect on a
     * long-lived scope, called once from Main.kt.
     */
    suspend fun followAppCharacterSelection() {
        AppState.selectedCharId.collect { charId ->
            if (charId == null) return@collect
            withContext(Dispatchers.IO) {
                val character = CharacterDao.getById(charId) ?: return@withContext
                val identity = ensureIdentityForCharacter(character)
                if (NostrIdentityDao.getActive()?.pubkey != identity.pubkey) {
                    NostrIdentityDao.setActive(identity.pubkey)
                    NostrRelayManager.restart()
                }
            }
        }
    }

    private fun persist(
        keyPair: KeyPair,
        label: String,
        characterId: Int?,
    ): NostrIdentity {
        val pubkeyHex = QuartzGateway.pubKeyHex(keyPair)
        val privKeyHex = requireNotNull(QuartzGateway.privKeyHex(keyPair)) { "generated/imported keypair unexpectedly read-only" }
        NostrIdentityDao.insert(pubkeyHex, NostrKeyCrypto.encrypt(privKeyHex), label, characterId)
        return NostrIdentity(pubkeyHex, label, keyPair, characterId)
    }

    private fun rowToIdentity(row: NostrIdentityModel): NostrIdentity {
        val privKeyHex = requireNotNull(NostrKeyCrypto.decrypt(row.encryptedPrivkey)) { "corrupt or undecryptable identity key" }
        return NostrIdentity(row.pubkey, row.label, KeyPair(privKey = privKeyHex.hexToByteArray()), row.characterId)
    }
}
