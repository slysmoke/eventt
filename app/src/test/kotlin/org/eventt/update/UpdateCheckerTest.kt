package org.eventt.update

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class UpdateCheckerTest {
    @Test
    fun `a higher patch version is newer`() {
        UpdateChecker.isNewer("1.0.1", "1.0.0") shouldBe true
    }

    @Test
    fun `a lower version is not newer`() {
        UpdateChecker.isNewer("1.0.0", "1.0.1") shouldBe false
    }

    @Test
    fun `identical versions are not newer`() {
        UpdateChecker.isNewer("1.0.0", "1.0.0") shouldBe false
    }

    @Test
    fun `a higher major version wins over higher minor and patch`() {
        UpdateChecker.isNewer("2.0.0", "1.9.9") shouldBe true
    }

    @Test
    fun `a missing trailing segment is treated as zero`() {
        UpdateChecker.isNewer("1.0", "1.0.0") shouldBe false
        UpdateChecker.isNewer("1.0.1", "1.0") shouldBe true
    }

    @Test
    fun `non-numeric segments are dropped rather than failing the comparison`() {
        // "0-beta" doesn't parse as an Int and is filtered out, so "1.0.0-beta" behaves as "1.0"
        UpdateChecker.isNewer("1.0.1", "1.0.0-beta") shouldBe true
    }
}
