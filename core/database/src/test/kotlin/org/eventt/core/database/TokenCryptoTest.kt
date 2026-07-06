package org.eventt.core.database

import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.Base64

class TokenCryptoTest {
    @TempDir
    lateinit var tempDir: File

    @BeforeEach
    fun setUp() {
        // A fresh, never-before-seen path per test — never the real ~/.eve-trader/token.key.
        TokenCrypto.keyFile = File(tempDir, "token.key")
    }

    @Test
    fun `encrypt then decrypt round-trips the original plaintext`() {
        val encrypted = TokenCrypto.encrypt("super-secret-refresh-token")

        TokenCrypto.decrypt(encrypted) shouldBe "super-secret-refresh-token"
    }

    @Test
    fun `two encryptions of the same plaintext produce different ciphertext but both decrypt correctly`() {
        val a = TokenCrypto.encrypt("same-value")
        val b = TokenCrypto.encrypt("same-value")

        (a == b) shouldBe false
        TokenCrypto.decrypt(a) shouldBe "same-value"
        TokenCrypto.decrypt(b) shouldBe "same-value"
    }

    @Test
    fun `decrypting garbage input returns null instead of throwing`() {
        TokenCrypto.decrypt("not valid base64 at all!!").shouldBeNull()
    }

    @Test
    fun `decrypting well-formed but bogus ciphertext returns null instead of throwing`() {
        val bogus = Base64.getEncoder().encodeToString(ByteArray(40))

        TokenCrypto.decrypt(bogus).shouldBeNull()
    }

    @Test
    fun `data encrypted under one key file cannot be decrypted under another`() {
        val encrypted = TokenCrypto.encrypt("cross-key-token")

        TokenCrypto.keyFile = File(tempDir, "other-token.key")

        TokenCrypto.decrypt(encrypted).shouldBeNull()
    }

    @Test
    fun `the key file is created on first use and holds a 32-byte AES-256 key`() {
        TokenCrypto.keyFile.exists() shouldBe false

        TokenCrypto.encrypt("anything")

        TokenCrypto.keyFile.exists() shouldBe true
        TokenCrypto.keyFile.readBytes().size shouldBe 32
    }
}
