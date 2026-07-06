package org.eventt.core.database

import java.io.File
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Encrypts character OAuth tokens at rest with a local, per-machine AES-256-GCM key.
 *
 * The key lives in its own file next to the database (not the database itself, so a copy of
 * the .db alone is useless) with owner-only permissions. This defends against another local
 * account — or a copy of just the .db file — reading tokens directly; it does not defend
 * against a compromised account under which the app itself runs.
 */
object TokenCrypto {
    private const val ALGO = "AES/GCM/NoPadding"
    private const val KEY_SIZE_BYTES = 32
    private const val GCM_IV_BYTES = 12
    private const val GCM_TAG_BITS = 128

    private val keyFile: File by lazy {
        File(System.getProperty("user.home"), ".eve-trader/token.key")
    }

    private val key: SecretKeySpec by lazy {
        if (!keyFile.exists()) {
            keyFile.parentFile?.mkdirs()
            val bytes = ByteArray(KEY_SIZE_BYTES).also { SecureRandom().nextBytes(it) }
            keyFile.writeBytes(bytes)
            restrictToOwner(keyFile)
        }
        SecretKeySpec(keyFile.readBytes(), "AES")
    }

    private fun restrictToOwner(file: File) {
        // Best-effort: drop group/other access. Maps to real POSIX perms on Linux/macOS;
        // on Windows the legacy File API only affects the ACL for "Everyone", which still
        // helps, but isn't a full per-user ACL.
        file.setReadable(false, false)
        file.setWritable(false, false)
        file.setReadable(true, true)
        file.setWritable(true, true)
    }

    fun encrypt(plaintext: String): String {
        val iv = ByteArray(GCM_IV_BYTES).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance(ALGO)
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
        val ciphertext = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        return Base64.getEncoder().encodeToString(iv + ciphertext)
    }

    /** Returns null if [stored] isn't validly encrypted (e.g. pre-encryption plaintext, or a lost key) — callers should treat that as an invalid/expired token rather than crash. */
    fun decrypt(stored: String): String? {
        return runCatching {
            val raw = Base64.getDecoder().decode(stored)
            val iv = raw.copyOfRange(0, GCM_IV_BYTES)
            val ciphertext = raw.copyOfRange(GCM_IV_BYTES, raw.size)
            val cipher = Cipher.getInstance(ALGO)
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
            String(cipher.doFinal(ciphertext), Charsets.UTF_8)
        }.getOrNull()
    }
}
