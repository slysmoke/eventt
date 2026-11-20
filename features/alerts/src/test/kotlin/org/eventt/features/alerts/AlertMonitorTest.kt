package org.eventt.features.alerts

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockkObject
import io.mockk.unmockkObject
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.eventt.core.database.AlertDao
import org.eventt.core.esi.EsiClient
import org.eventt.core.model.PriceAlertModel
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

private const val DEFAULT_REGION_ID = 10000002 // The Forge (Jita)

class AlertMonitorTest {
    @BeforeEach
    fun setUp() {
        mockkObject(AlertDao)
        mockkObject(EsiClient)
    }

    @AfterEach
    fun tearDown() {
        AlertMonitor.triggered.value.forEach { AlertMonitor.dismiss(it) }
        unmockkObject(AlertDao)
        unmockkObject(EsiClient)
    }

    private fun order(
        price: Double,
        isBuy: Boolean,
    ): Map<String, Any?> = mapOf("price" to price, "is_buy_order" to isBuy)

    @Test
    fun `dismiss removes only the matching alert, leaving others triggered`() =
        runTest {
            every { AlertDao.getEnabled() } returns
                listOf(
                    PriceAlertModel(id = 1, typeId = 34, targetPrice = 5.0, condition = "above", orderType = "sell"),
                    PriceAlertModel(id = 2, typeId = 35, targetPrice = 5.0, condition = "above", orderType = "sell"),
                )
            every { EsiClient.getMarketRegionOrders(any(), any(), any()) } returns listOf(order(10.0, isBuy = false))
            every { AlertDao.markTriggered(any()) } returns Unit

            AlertMonitor.checkAlerts()
            val (first, second) = AlertMonitor.triggered.value

            AlertMonitor.dismiss(first)

            AlertMonitor.triggered.value.map { it.id } shouldBe listOf(second.id)
        }

    @Test
    fun `no enabled alerts means no ESI calls and nothing triggers`() =
        runTest {
            every { AlertDao.getEnabled() } returns emptyList()

            AlertMonitor.checkAlerts()

            AlertMonitor.triggered.value.shouldBeEmpty()
            verify(exactly = 0) { EsiClient.getMarketRegionOrders(any(), any(), any()) }
        }

    @Test
    fun `an above-condition sell alert fires when the best sell price meets the target`() =
        runTest {
            every { AlertDao.getEnabled() } returns
                listOf(PriceAlertModel(id = 1, typeId = 34, targetPrice = 5.0, condition = "above", orderType = "sell"))
            every { EsiClient.getMarketRegionOrders(any(), any(), any()) } returns
                listOf(order(5.0, isBuy = false), order(6.0, isBuy = false))
            every { AlertDao.markTriggered(1) } returns Unit

            AlertMonitor.checkAlerts()

            AlertMonitor.triggered.value
                .single()
                .id shouldBe 1
            verify { AlertDao.markTriggered(1) }
        }

    @Test
    fun `a below-condition buy alert fires when the best buy price meets the target`() =
        runTest {
            every { AlertDao.getEnabled() } returns
                listOf(PriceAlertModel(id = 1, typeId = 34, targetPrice = 10.0, condition = "below", orderType = "buy"))
            every { EsiClient.getMarketRegionOrders(any(), any(), any()) } returns
                listOf(order(9.0, isBuy = true), order(8.0, isBuy = true))
            every { AlertDao.markTriggered(1) } returns Unit

            AlertMonitor.checkAlerts()

            AlertMonitor.triggered.value
                .single()
                .id shouldBe 1
        }

    @Test
    fun `an alert whose condition isn't met does not fire`() =
        runTest {
            every { AlertDao.getEnabled() } returns
                listOf(PriceAlertModel(id = 1, typeId = 34, targetPrice = 100.0, condition = "above", orderType = "sell"))
            every { EsiClient.getMarketRegionOrders(any(), any(), any()) } returns listOf(order(5.0, isBuy = false))

            AlertMonitor.checkAlerts()

            AlertMonitor.triggered.value.shouldBeEmpty()
            verify(exactly = 0) { AlertDao.markTriggered(any()) }
        }

    @Test
    fun `alerts with no region use the default region, and share one ESI call per type`() =
        runTest {
            every { AlertDao.getEnabled() } returns
                listOf(
                    PriceAlertModel(id = 1, typeId = 34, targetPrice = 100.0, condition = "above", orderType = "sell", regionId = 0),
                    PriceAlertModel(id = 2, typeId = 34, targetPrice = 200.0, condition = "above", orderType = "sell", regionId = 0),
                )
            every { EsiClient.getMarketRegionOrders(DEFAULT_REGION_ID, any(), 34) } returns emptyList()

            AlertMonitor.checkAlerts()

            verify(exactly = 1) { EsiClient.getMarketRegionOrders(DEFAULT_REGION_ID, any(), 34) }
        }

    @Test
    fun `a failure loading alerts is swallowed rather than crashing the poll loop`() =
        runTest {
            every { AlertDao.getEnabled() } throws RuntimeException("db unavailable")

            AlertMonitor.checkAlerts()

            AlertMonitor.triggered.value.shouldBeEmpty()
        }

    @Test
    fun `a failed ESI call for one group doesn't prevent other groups from firing`() =
        runTest {
            every { AlertDao.getEnabled() } returns
                listOf(
                    PriceAlertModel(id = 1, typeId = 34, targetPrice = 5.0, condition = "above", orderType = "sell", regionId = 1),
                    PriceAlertModel(id = 2, typeId = 35, targetPrice = 5.0, condition = "above", orderType = "sell", regionId = 1),
                )
            every { EsiClient.getMarketRegionOrders(1, any(), 34) } throws RuntimeException("ESI down")
            every { EsiClient.getMarketRegionOrders(1, any(), 35) } returns listOf(order(10.0, isBuy = false))
            every { AlertDao.markTriggered(2) } returns Unit

            AlertMonitor.checkAlerts()

            AlertMonitor.triggered.value.map { it.id } shouldContainExactlyInAnyOrder listOf(2)
        }

    @Test
    fun `repeated checks accumulate newly triggered alerts instead of replacing them`() =
        runTest {
            every { AlertDao.getEnabled() } returns
                listOf(PriceAlertModel(id = 1, typeId = 34, targetPrice = 5.0, condition = "above", orderType = "sell"))
            every { EsiClient.getMarketRegionOrders(any(), any(), 34) } returns listOf(order(10.0, isBuy = false))
            every { AlertDao.markTriggered(1) } returns Unit
            AlertMonitor.checkAlerts()

            every { AlertDao.getEnabled() } returns
                listOf(PriceAlertModel(id = 2, typeId = 35, targetPrice = 5.0, condition = "above", orderType = "sell"))
            every { EsiClient.getMarketRegionOrders(any(), any(), 35) } returns listOf(order(10.0, isBuy = false))
            every { AlertDao.markTriggered(2) } returns Unit
            AlertMonitor.checkAlerts()

            AlertMonitor.triggered.value.map { it.id } shouldContainExactlyInAnyOrder listOf(1, 2)
        }

    @Test
    fun `a live z alert fires at today's z level, never under its floor`() =
        runTest {
            // Floor (break-even) 5; the z = +1 price is 8 on the first check and 6 on the second.
            every { AlertDao.getEnabled() } returns
                listOf(PriceAlertModel(id = 7, typeId = 34, targetPrice = 5.0, condition = "above", orderType = "sell", zTarget = 1.0))
            every { EsiClient.getMarketRegionOrders(any(), any(), any()) } returns listOf(order(7.0, isBuy = false))
            every { AlertDao.markTriggered(7) } returns Unit
            val real = AlertMonitor.zPrice
            try {
                AlertMonitor.zPrice = { _, _, _ -> 8.0 }
                AlertMonitor.checkAlerts()
                // Ask 7 is over the floor but under the level: quiet, and the level is published.
                AlertMonitor.triggered.value.shouldBeEmpty()
                AlertMonitor.levels.value[7] shouldBe 8.0

                // The market cooled, the level came down to 6: the same ask now fires, at 6.
                AlertMonitor.zPrice = { _, _, _ -> 6.0 }
                AlertMonitor.checkAlerts()
                AlertMonitor.triggered.value.map { it.targetPrice } shouldBe listOf(6.0)

                // A level under the floor is held at the floor.
                AlertMonitor.zPrice = { _, _, _ -> 3.0 }
                AlertMonitor.checkAlerts()
                AlertMonitor.levels.value[7] shouldBe 5.0

                // No history for a z-score: nothing to compare with, so it stays quiet.
                AlertMonitor.triggered.value.forEach { AlertMonitor.dismiss(it) }
                AlertMonitor.zPrice = { _, _, _ -> null }
                AlertMonitor.checkAlerts()
                AlertMonitor.triggered.value.shouldBeEmpty()
            } finally {
                AlertMonitor.zPrice = real
            }
        }
}
