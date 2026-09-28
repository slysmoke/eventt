package org.eventt.ui.common

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class FormatUtilsTest {
    @Test
    fun `prices and ISK use K M B T with enough digits to tell ticks apart`() {
        formatPriceAbbr(17.38) shouldBe "17.38"
        formatPriceAbbr(1_105.0) shouldBe "1.105K"
        formatPriceAbbr(1_104.0) shouldBe "1.104K"
        formatPriceAbbr(17_380.0) shouldBe "17.38K"
        formatPriceAbbr(185_000.0) shouldBe "185.00K"
        formatPriceAbbr(1_821_500.0) shouldBe "1.822M"
        formatIsk(92_880_000.0) shouldBe "92.88M"
        formatIsk(4_450_000_000.0) shouldBe "4.450B"
        formatIsk(2_500_000_000_000.0) shouldBe "2.500T"
    }

    @Test
    fun `negative values are abbreviated too`() {
        formatPriceAbbr(-71_480_000.0) shouldBe "-71.48M"
        formatIsk(-1_500.0) shouldBe "-1.500K"
    }

    @Test
    fun `rounding up rolls over into the next unit`() {
        formatIsk(999_999.0) shouldBe "1.000M"
        formatVolume(999_990L) shouldBe "1.0M"
    }

    @Test
    fun `volumes use one decimal and an uppercase K`() {
        formatVolume(83L) shouldBe "83"
        formatVolume(329_841L) shouldBe "329.8K"
        formatVolume(1_271_849L) shouldBe "1.3M"
        formatVolume(4_500.0) shouldBe "4.5K"
    }
}
