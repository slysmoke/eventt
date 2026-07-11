package org.eventt.features.overlay

import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.should
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class ClipboardParserTest {
    @Test
    fun `parses a sell row (5 fields) into a ParsedOrder`() {
        val order =
            ClipboardParser.parse(
                "10\t1,234\t5.50 ISK\tJita IV - Moon 4 - Caldari Navy Assembly Plant\t2024-01-15 12:00:00",
            )

        order!!.price should (5.50 plusOrMinus 0.0001)
        order.volume shouldBe 1234L
        order.location shouldBe "Jita IV - Moon 4 - Caldari Navy Assembly Plant"
        order.isBuy shouldBe false
    }

    @Test
    fun `parses a buy row (7 fields) and flags it as a buy`() {
        val order = ClipboardParser.parse("5\t500\t4.75 ISK\tAmarr VIII (Oris)\t10\t100\t2024-02-01")

        order!!.price should (4.75 plusOrMinus 0.0001)
        order.volume shouldBe 500L
        order.location shouldBe "Amarr VIII (Oris)"
        order.isBuy shouldBe true
    }

    @Test
    fun `only the first line of multi-line clipboard text is parsed`() {
        val order =
            ClipboardParser.parse(
                "10\t1,234\t5.50 ISK\tJita\t2024-01-15\nnot a valid row at all",
            )

        order!!.price should (5.50 plusOrMinus 0.0001)
        order.location shouldBe "Jita"
    }

    @Test
    fun `null clipboard text yields no order`() {
        ClipboardParser.parse(null).shouldBeNull()
    }

    @Test
    fun `fewer than four fields yields no order`() {
        ClipboardParser.parse("a\tb\tc").shouldBeNull()
    }

    @Test
    fun `a row with no ISK-tagged field yields no order`() {
        ClipboardParser.parse("10\t1234\t5.50\tJita\t2024-01-15").shouldBeNull()
    }

    @Test
    fun `an ISK field in the very first column yields no order`() {
        ClipboardParser.parse("5.50 ISK\t10\tJita\t2024-01-15").shouldBeNull()
    }

    @Test
    fun `a non-numeric volume field falls back to zero instead of failing`() {
        val order = ClipboardParser.parse("10\tN/A\t5.50 ISK\tJita\t2024-01-15")

        order!!.volume shouldBe 0L
        order.price should (5.50 plusOrMinus 0.0001)
    }

    @Test
    fun `a missing location field (ISK is the last column) yields an empty location`() {
        val order = ClipboardParser.parse("10\t1234\t999\t5.50 ISK")

        order!!.location shouldBe ""
    }

    @Test
    fun `parses a PLEX sell row (3 fields, no station) into a ParsedOrder`() {
        val order = ClipboardParser.parse("164\t4,705,000.00 ISK\t88d 23h 14m 37s")

        order!!.price should (4_705_000.00 plusOrMinus 0.0001)
        order.volume shouldBe 164L
        order.location shouldBe ""
        order.isBuy shouldBe false
    }

    @Test
    fun `parses a PLEX buy row (4 fields, no station) and flags it as a buy`() {
        val order = ClipboardParser.parse("75\t4,550,000.00 ISK\t1\t6d 23h 59m 28s")

        order!!.price should (4_550_000.00 plusOrMinus 0.0001)
        order.volume shouldBe 75L
        order.location shouldBe ""
        order.isBuy shouldBe true
    }
}
