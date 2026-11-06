package org.eventt.features.p2pmarket

import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockkObject
import io.mockk.unmockkObject
import org.eventt.core.esi.EsiClient
import org.eventt.core.esi.EsiOfflineException
import org.eventt.core.nostr.OrderSide
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test

class SavingsBadgeServiceTest {
    @AfterEach
    fun tearDown() = unmockkObject(EsiClient)

    @Test
    fun `an offline EVE server yields no price instead of throwing into the UI`() {
        mockkObject(EsiClient)
        every { EsiClient.getMarketRegionOrders(any(), any(), any()) } throws EsiOfflineException("/markets/10000002/orders/")

        SavingsBadgeService.computeSavings(34, 10000002, OrderSide.SELL, 5.0, 8.0) shouldBe null
        SavingsBadgeService.recommendedPrice(34, OrderSide.SELL, 8.0, 3.0) shouldBe null
    }

    @Test
    fun `the best price is the cheapest sell and the highest buy`() {
        mockkObject(EsiClient)
        every { EsiClient.getMarketRegionOrders(any(), any(), any()) } returns listOf(mapOf("price" to 10.0), mapOf("price" to 20.0))

        SavingsBadgeService.recommendedPrice(34, OrderSide.SELL, 0.0, 0.0)?.basePrice shouldBe 10.0
        SavingsBadgeService.recommendedPrice(34, OrderSide.BUY, 0.0, 0.0)?.basePrice shouldBe 20.0
    }
}
