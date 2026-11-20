package org.eventt.features.market

import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class MyTradesBreakEvenTest {
    @Test
    fun `break-even covers the buy broker fee, so the ruler from cost to it nets zero`() {
        val t = MyTrades(100, 3920.0, null, sellFeePct = 4.5, buyFeePct = 1.15, emptyList(), emptyList(), emptyList(), emptyList())
        val be = t.breakEven!!

        be shouldBe (3920.0 * 1.0115 / 0.955 plusOrMinus 1e-9)
        // The margin ruler's own formula, from the cost line up to the break-even line.
        (be * (1 - 4.5 / 100) - 3920.0 * (1 + 1.15 / 100)) shouldBe (0.0 plusOrMinus 1e-9)
    }
}
