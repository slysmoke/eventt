package org.eventt.ui.common

import androidx.compose.ui.text.AnnotatedString
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

    @Test
    fun `number fields show digits in groups of three and map the cursor across the spaces`() {
        val t = DigitGroupTransformation.filter(AnnotatedString("3500000.5"))
        t.text.text shouldBe "3 500 000.5"
        t.offsetMapping.originalToTransformed(1) shouldBe 2
        t.offsetMapping.originalToTransformed(2) shouldBe 3
        t.offsetMapping.originalToTransformed(9) shouldBe 11
        t.offsetMapping.transformedToOriginal(2) shouldBe 1
        t.offsetMapping.transformedToOriginal(11) shouldBe 9
        DigitGroupTransformation.filter(AnnotatedString("320")).text.text shouldBe "320"
    }
}
