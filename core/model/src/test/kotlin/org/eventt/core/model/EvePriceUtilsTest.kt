package org.eventt.core.model

import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class EvePriceUtilsTest {
    @Test
    fun `undercut steps one tick below a sell price`() {
        eveUndercutPrice(100_100.0) shouldBe 100_000.0
        eveUndercutPrice(4.99) shouldBe (4.98 plusOrMinus 1e-9)
        eveUndercutPrice(1_000_000.0) shouldBe 999_000.0
    }

    @Test
    fun `outbid steps one tick above a buy price`() {
        eveOutbidPrice(100_100.0) shouldBe 100_200.0
        eveOutbidPrice(999.9) shouldBe (1000.0 plusOrMinus 1e-9)
        eveOutbidPrice(4.99) shouldBe (5.0 plusOrMinus 1e-9)
    }

    @Test
    fun `undercut never goes below the minimum EVE price`() {
        eveUndercutPrice(0.01) shouldBe 0.01
    }
}
