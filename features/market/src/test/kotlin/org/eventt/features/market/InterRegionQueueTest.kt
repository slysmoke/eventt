package org.eventt.features.market

import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.should
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockkObject
import io.mockk.unmockkObject
import org.eventt.core.esi.EsiClient
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

private const val TOLERANCE = 0.0001

class InterRegionQueueTest {
    @BeforeEach
    fun setUp() {
        mockkObject(EsiClient)
        every { EsiClient.openMarketWindow(any(), any()) } returns Unit
    }

    @AfterEach
    fun tearDown() {
        InterRegionQueue.clear()
        InterRegionQueue.copyVolume = true
        unmockkObject(EsiClient)
    }

    @Test
    fun `priceToSet steps one tick above a competitive-bid price`() {
        val item = PendingRegionItem(charId = 1, typeId = 34, typeName = "Tritanium", price = 100.0, isCompetitiveBid = true, volume = 500)

        // step(100.0) = 0.1 -> outbid the current competing buy order by one tick
        item.priceToSet should (100.1 plusOrMinus TOLERANCE)
    }

    @Test
    fun `priceToSet is the price as-is when it's not a competitive bid`() {
        val item = PendingRegionItem(charId = 1, typeId = 34, typeName = "Tritanium", price = 100.0, isCompetitiveBid = false, volume = 500)

        item.priceToSet should (100.0 plusOrMinus TOLERANCE)
    }

    private fun item(
        typeId: Int,
        typeName: String,
    ) = PendingRegionItem(charId = 1, typeId = typeId, typeName = typeName, price = 10.0, isCompetitiveBid = true, volume = 100)

    @Test
    fun `with copyVolume on, each item takes two presses before advancing`() {
        InterRegionQueue.copyVolume = true
        InterRegionQueue.update(listOf(item(1, "A"), item(2, "B")))

        InterRegionQueue.processNext() // item 1, PRICE
        InterRegionQueue.currentTypeId.value shouldBe 1
        InterRegionQueue.currentPosition shouldBe 1

        InterRegionQueue.processNext() // item 1, VOLUME -> advances
        InterRegionQueue.currentTypeId.value shouldBe 1
        InterRegionQueue.currentPosition shouldBe 2

        InterRegionQueue.processNext() // item 2, PRICE
        InterRegionQueue.currentTypeId.value shouldBe 2

        InterRegionQueue.processNext() // item 2, VOLUME -> advances, wraps
        InterRegionQueue.currentPosition shouldBe 1
    }

    @Test
    fun `with copyVolume off, every press advances directly to the next item`() {
        InterRegionQueue.copyVolume = false
        InterRegionQueue.update(listOf(item(1, "A"), item(2, "B")))

        InterRegionQueue.processNext()
        InterRegionQueue.currentTypeId.value shouldBe 1

        InterRegionQueue.processNext()
        InterRegionQueue.currentTypeId.value shouldBe 2

        InterRegionQueue.processNext() // wraps
        InterRegionQueue.currentTypeId.value shouldBe 1
    }

    @Test
    fun `processNext on an empty queue does nothing`() {
        InterRegionQueue.processNext()

        InterRegionQueue.currentTypeId.value shouldBe null
    }

    @Test
    fun `clear resets the queue, position, and current item`() {
        InterRegionQueue.update(listOf(item(1, "A")))
        InterRegionQueue.processNext()

        InterRegionQueue.clear()

        InterRegionQueue.size shouldBe 0
        InterRegionQueue.currentPosition shouldBe 0
        InterRegionQueue.currentTypeId.value shouldBe null
    }
}
