package org.eventt.features.orders

import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.should
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockkObject
import io.mockk.unmockkObject
import org.eventt.core.esi.EsiClient
import org.eventt.core.model.eveSigFigStep
import org.eventt.core.model.formatEveSigFigPrice
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

private const val TOLERANCE = 0.0001

class PendingOrdersQueueTest {
    @BeforeEach
    fun setUp() {
        mockkObject(EsiClient)
        every { EsiClient.openMarketWindow(any(), any()) } returns Unit
    }

    @AfterEach
    fun tearDown() {
        PendingOrdersQueue.clear()
        PendingOrdersQueue.onlyBeaten = false
        unmockkObject(EsiClient)
    }

    // ── eveSigFigStep / formatEveSigFigPrice ───────────────────────────────

    @Test
    fun `eveSigFigStep is 4 sig figs for a multi-million ISK price`() {
        eveSigFigStep(43_150_000.0) should (10_000.0 plusOrMinus TOLERANCE)
    }

    @Test
    fun `eveSigFigStep is a cent for a low double-digit price`() {
        eveSigFigStep(45.56) should (0.01 plusOrMinus TOLERANCE)
    }

    @Test
    fun `eveSigFigStep is a tenth for a triple-digit price`() {
        eveSigFigStep(135.5) should (0.1 plusOrMinus TOLERANCE)
    }

    @Test
    fun `eveSigFigStep floors non-positive prices to a cent`() {
        eveSigFigStep(0.0) should (0.01 plusOrMinus TOLERANCE)
        eveSigFigStep(-5.0) should (0.01 plusOrMinus TOLERANCE)
    }

    @Test
    fun `formatEveSigFigPrice uses the right decimal count for the price's magnitude`() {
        formatEveSigFigPrice(43_150_000.0) shouldBe "43150000"
        formatEveSigFigPrice(135.5) shouldBe "135.5"
        formatEveSigFigPrice(45.56) shouldBe "45.56"
    }

    @Test
    fun `formatEveSigFigPrice floors non-positive prices to 0-point-01`() {
        formatEveSigFigPrice(0.0) shouldBe "0.01"
    }

    // ── PendingOrdersQueue ──────────────────────────────────────────────────

    private fun order(
        id: Long,
        typeName: String,
        isBeaten: Boolean,
    ) = PendingOrder(
        charId = 1,
        orderId = id,
        typeId = 34,
        typeName = typeName,
        isBuyOrder = false,
        regionId = 10000002,
        ownPrice = 10.0,
        bestCompetingPrice = null,
        isBeaten = isBeaten,
    )

    @Test
    fun `update sorts beaten orders first, then alphabetically within each group`() {
        PendingOrdersQueue.update(
            listOf(
                order(1, "Zydrine", isBeaten = false),
                order(2, "Tritanium", isBeaten = true),
                order(3, "Isogen", isBeaten = false),
                order(4, "Mexallon", isBeaten = true),
            ),
        )

        PendingOrdersQueue.size shouldBe 4
        PendingOrdersQueue.currentPosition shouldBe 1
    }

    @Test
    fun `clear empties the queue and resets position and current order`() {
        PendingOrdersQueue.update(listOf(order(1, "Tritanium", isBeaten = false)))

        PendingOrdersQueue.clear()

        PendingOrdersQueue.size shouldBe 0
        PendingOrdersQueue.currentPosition shouldBe 0
        PendingOrdersQueue.currentOrderId.value shouldBe null
    }

    @Test
    fun `processNext cycles through the queue in order and wraps around`() {
        PendingOrdersQueue.update(
            listOf(
                order(10, "Tritanium", isBeaten = false),
                order(20, "Zydrine", isBeaten = false),
            ),
        )

        PendingOrdersQueue.processNext()
        PendingOrdersQueue.currentOrderId.value shouldBe 10L
        PendingOrdersQueue.currentPosition shouldBe 2

        PendingOrdersQueue.processNext()
        PendingOrdersQueue.currentOrderId.value shouldBe 20L
        PendingOrdersQueue.currentPosition shouldBe 1

        PendingOrdersQueue.processNext()
        PendingOrdersQueue.currentOrderId.value shouldBe 10L
    }

    @Test
    fun `queue update keeps the cycle position by orderId`() {
        PendingOrdersQueue.update(
            listOf(
                order(10, "Isogen", isBeaten = false),
                order(20, "Tritanium", isBeaten = false),
                order(30, "Zydrine", isBeaten = false),
            ),
        )
        PendingOrdersQueue.processNext()
        PendingOrdersQueue.currentOrderId.value shouldBe 10L

        // A refresh replaces the queue (same orders, one newly beaten and resorted to the front) —
        // the cycle must resume after order 10, not restart at the head.
        PendingOrdersQueue.update(
            listOf(
                order(10, "Isogen", isBeaten = false),
                order(20, "Tritanium", isBeaten = false),
                order(30, "Zydrine", isBeaten = true),
            ),
        )
        PendingOrdersQueue.processNext()
        PendingOrdersQueue.currentOrderId.value shouldBe 20L
    }

    @Test
    fun `onlyBeaten cycles just the beaten orders`() {
        PendingOrdersQueue.onlyBeaten = true
        PendingOrdersQueue.update(
            listOf(
                order(10, "Isogen", isBeaten = false),
                order(20, "Tritanium", isBeaten = true),
                order(30, "Zydrine", isBeaten = true),
            ),
        )

        PendingOrdersQueue.size shouldBe 2

        PendingOrdersQueue.processNext()
        PendingOrdersQueue.currentOrderId.value shouldBe 20L
        PendingOrdersQueue.processNext()
        PendingOrdersQueue.currentOrderId.value shouldBe 30L
        PendingOrdersQueue.processNext()
        PendingOrdersQueue.currentOrderId.value shouldBe 20L
    }

    @Test
    fun `startFrom makes the next press act on the chosen order, then the cycle continues`() {
        PendingOrdersQueue.update(
            listOf(
                order(10, "Isogen", isBeaten = false),
                order(20, "Tritanium", isBeaten = false),
                order(30, "Zydrine", isBeaten = false),
            ),
        )
        PendingOrdersQueue.processNext()
        PendingOrdersQueue.currentOrderId.value shouldBe 10L

        PendingOrdersQueue.startFrom(30L)
        PendingOrdersQueue.processNext()
        PendingOrdersQueue.currentOrderId.value shouldBe 30L

        // One-shot: after acting on 30 the cycle continues from there, wrapping to the head.
        PendingOrdersQueue.processNext()
        PendingOrdersQueue.currentOrderId.value shouldBe 10L
    }

    @Test
    fun `processNext on an empty queue does nothing`() {
        PendingOrdersQueue.processNext()

        PendingOrdersQueue.currentOrderId.value shouldBe null
    }

    @Test
    fun `PendingOrder priceToSet uses the sigfig-adjusted competing price when beaten`() {
        val beatenSell = order(1, "Tritanium", isBeaten = true).copy(bestCompetingPrice = 100.0)
        val beatenBuy = beatenSell.copy(isBuyOrder = true)
        val notBeaten = order(2, "Tritanium", isBeaten = false).copy(ownPrice = 42.0, bestCompetingPrice = 100.0)

        // step(100.0) = 0.1 -> undercut a sell by one step, overbid a buy by one step
        beatenSell.priceToSet should (99.9 plusOrMinus TOLERANCE)
        beatenBuy.priceToSet should (100.1 plusOrMinus TOLERANCE)
        notBeaten.priceToSet should (42.0 plusOrMinus TOLERANCE)
    }
}
