package org.eventt.features.market

import io.kotest.matchers.floats.plusOrMinus
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class ChartViewTest {
    @Test
    fun `preset shows the last N days`() {
        presetView(n = 400, days = 30) shouldBe ChartView(370f, 399f)
        presetView(n = 20, days = 30) shouldBe ChartView(0f, 19f)
    }

    @Test
    fun `zoom keeps the day under the cursor fixed`() {
        val v = ChartView(100f, 200f)
        val z = v.zoomed(factor = 0.5f, anchor = 0.25f, n = 400) // pivot = 125
        z.span shouldBe (50f plusOrMinus 1e-3f)
        (z.start + 0.25f * z.span) shouldBe (125f plusOrMinus 1e-3f)
    }

    @Test
    fun `zoom is bounded by the minimum span and the whole history`() {
        ChartView(100f, 110f).zoomed(0.1f, 0.5f, 400).span shouldBe 7f
        ChartView(100f, 300f).zoomed(10f, 0.5f, 400) shouldBe ChartView(0f, 399f)
    }

    @Test
    fun `pan stops at either end without shrinking the window`() {
        ChartView(370f, 399f).panned(50f, 400) shouldBe ChartView(370f, 399f)
        ChartView(10f, 40f).panned(-50f, 400) shouldBe ChartView(0f, 30f)
    }
}
