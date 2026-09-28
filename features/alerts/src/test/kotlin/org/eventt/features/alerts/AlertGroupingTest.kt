package org.eventt.features.alerts

import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import org.eventt.core.model.PriceAlertModel
import org.junit.jupiter.api.Test

class AlertGroupingTest {
    private fun alert(
        id: Int,
        typeId: Int,
        target: Double,
        condition: String = "below",
        orderType: String = "buy",
        triggered: Boolean = false,
    ) = PriceAlertModel(
        id = id,
        typeId = typeId,
        typeName = "T$typeId",
        targetPrice = target,
        condition = condition,
        regionId = 10000002,
        orderType = orderType,
        triggered = triggered,
    )

    private val quotes =
        mapOf(
            (1 to 10000002) to AlertMonitor.Quote(bid = 100.0, ask = 110.0),
            (2 to 10000002) to AlertMonitor.Quote(bid = 50.0, ask = 55.0),
        )

    @Test
    fun `distance is how far the watched side still has to move`() {
        distancePct(alert(1, 1, 95.0), quotes[1 to 10000002])!! shouldBe (-5.0 plusOrMinus 1e-9)
        distancePct(alert(1, 1, 121.0, condition = "above", orderType = "sell"), quotes[1 to 10000002])!! shouldBe (10.0 plusOrMinus 1e-9)
        distancePct(alert(1, 3, 1.0), null) shouldBe null
    }

    @Test
    fun `alerts group per item, nearest-to-firing first within and across groups`() {
        val groups =
            groupAlerts(
                listOf(
                    alert(1, 1, 80.0), // -20%
                    alert(2, 1, 95.0), // -5%
                    alert(3, 2, 49.0), // -2%
                    alert(4, 2, 10.0, triggered = true),
                ),
                quotes,
            )
        groups.map { it.typeId } shouldBe listOf(2, 1)
        groups.first { it.typeId == 1 }.alerts.map { it.id } shouldBe listOf(2, 1)
        // Triggered alerts sort after the live ones in their group.
        groups.first { it.typeId == 2 }.alerts.map { it.id } shouldBe listOf(3, 4)
    }
}
