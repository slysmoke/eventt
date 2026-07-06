package org.eventt.core.auth

import io.kotest.matchers.maps.shouldBeEmpty
import io.kotest.matchers.maps.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldMatch
import org.junit.jupiter.api.Test

class SsoAuthManagerTest {
    @Test
    fun `generateCodeVerifier produces a URL-safe, unpadded base64 string with real entropy`() {
        val verifier = SsoAuthManager.generateCodeVerifier()

        // 32 random bytes, base64url-encoded without padding -> 43 chars, RFC 7636's own range (43-128)
        verifier shouldMatch Regex("^[A-Za-z0-9_-]{43}$")
        (SsoAuthManager.generateCodeVerifier() == SsoAuthManager.generateCodeVerifier()) shouldBe false
    }

    @Test
    fun `codeChallenge matches the RFC 7636 Appendix B worked example`() {
        // https://www.rfc-editor.org/rfc/rfc7636#appendix-B - the spec's own verifier/challenge pair.
        val verifier = "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"

        SsoAuthManager.codeChallenge(verifier) shouldBe "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM"
    }

    @Test
    fun `codeChallenge is deterministic and distinct per verifier`() {
        val a = SsoAuthManager.codeChallenge("verifier-one")
        val b = SsoAuthManager.codeChallenge("verifier-one")
        val c = SsoAuthManager.codeChallenge("verifier-two")

        a shouldBe b
        (a == c) shouldBe false
    }

    @Test
    fun `parseQueryString decodes simple key-value pairs`() {
        val result = SsoAuthManager.parseQueryString("code=abc123&state=xyz")

        result shouldContainExactly mapOf("code" to "abc123", "state" to "xyz")
    }

    @Test
    fun `parseQueryString URL-decodes both keys and values`() {
        val result = SsoAuthManager.parseQueryString("redirect%20uri=http%3A%2F%2Flocalhost%3A8000")

        result shouldContainExactly mapOf("redirect uri" to "http://localhost:8000")
    }

    @Test
    fun `parseQueryString skips pairs without an equals sign`() {
        val result = SsoAuthManager.parseQueryString("code=abc&malformed&state=xyz")

        result shouldContainExactly mapOf("code" to "abc", "state" to "xyz")
    }

    @Test
    fun `parseQueryString on an empty string yields no pairs`() {
        SsoAuthManager.parseQueryString("").shouldBeEmpty()
    }
}
