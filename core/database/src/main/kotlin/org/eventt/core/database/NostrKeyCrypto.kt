package org.eventt.core.database

import org.eventt.core.model.AppPaths
import java.io.File
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Encrypts the local Nostr identity's private key at rest, with its own AES-256-GCM key file
 * separate from [TokenCrypto]'s — rotating/compromising one secret domain (OAuth tokens vs. the
 * Nostr identity) shouldn't touch the other. Same shape as TokenCrypto otherwise; see it for the
 * threat-model rationale.
 */
object NostrKeyCrypto {
    private const val ALGO = "AES/GCM/NoPadding"
    private const val KEY_SIZE_BYTES = 32
    private const val GCM_IV_BYTES = 12
    private const val GCM_TAG_BITS = 128

    internal var keyFile: File = File(AppPaths.appDataDir, "nostr.key")

    private fun key(): SecretKeySpec {
        if (!keyFile.exists()) {
            keyFile.parentFile?.mkdirs()
            val bytes = ByteArray(KEY_SIZE_BYTES).also { SecureRandom().nextBytes(it) }
            keyFile.writeBytes(bytes)
            restrictToOwner(keyFile)
        }
        return SecretKeySpec(keyFile.readBytes(), "AES")
    }

    private fun restrictToOwner(file: File) {
        file.setReadable(false, false)
        file.setWritable(false, false)
        file.setReadable(true, true)
        file.setWritable(true, true)
    }

    fun encrypt(plaintext: String): String {
        val iv = ByteArray(GCM_IV_BYTES).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance(ALGO)
        cipher.init(Cipher.ENCRYPT_MODE, key(), GCMParameterSpec(GCM_TAG_BITS, iv))
        val ciphertext = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        return Base64.getEncoder().encodeToString(iv + ciphertext)
    }

    /** Returns null if [stored] isn't validly encrypted (e.g. a lost key) — callers should treat that as "no identity", not crash. */
    fun decrypt(stored: String): String? =
        runCatching {
            val raw = Base64.getDecoder().decode(stored)
            val iv = raw.copyOfRange(0, GCM_IV_BYTES)
            val ciphertext = raw.copyOfRange(GCM_IV_BYTES, raw.size)
            val cipher = Cipher.getInstance(ALGO)
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(GCM_TAG_BITS, iv))
            String(cipher.doFinal(ciphertext), Charsets.UTF_8)
        }.getOrNull()
}
