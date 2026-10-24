package org.eventt.features.contracts

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.runDesktopComposeUiTest
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import org.eventt.core.database.DatabaseManager
import org.eventt.core.model.ContractModel
import org.eventt.ui.theme.DarkColorScheme
import org.junit.jupiter.api.Test
import java.io.File
import java.time.Duration
import java.time.Instant
import javax.imageio.ImageIO

// Contract stats from synthetic couriers/exchanges, plus a headless render of the body written to
// build/contracts-screens/contracts.png for a look without launching the app.
@OptIn(ExperimentalTestApi::class)
class ContractsBodyTest {
    private val now = Instant.parse("2026-10-24T12:00:00Z")

    private fun at(hoursAgo: Long) = now.minus(Duration.ofHours(hoursAgo)).toString()

    private fun line(
        id: Int,
        type: String,
        status: String,
        issuedH: Long,
        doneH: Long? = null,
        acceptedH: Long? = null,
        reward: Double = 0.0,
        collateral: Double = 0.0,
        price: Double = 0.0,
        expiresInH: Long = 72,
        party: String? = "Push Industries",
        title: String = "",
    ) = ContractLine(
        ContractModel(
            contractId = id,
            issuerId = 1,
            issuerCorpId = 2,
            assigneeId = 0,
            acceptorId = if (party != null) 9 else 0,
            startStationId = 60003760,
            endStationId = 60008494,
            type = type,
            status = status,
            title = title,
            dateIssued = at(issuedH),
            dateExpired = now.plus(Duration.ofHours(expiresInH)).toString(),
            dateAccepted = acceptedH?.let(::at),
            dateCompleted = doneH?.let(::at),
            reward = reward,
            collateral = collateral,
            price = price,
        ),
        "Jita",
        "Amarr",
        party,
        null,
    )

    private val lines =
        listOf(
            line(1, "courier", "finished", 48, doneH = 24, acceptedH = 40, reward = 60e6, collateral = 3e9),
            line(2, "courier", "finished", 30, doneH = 28, acceptedH = 29, reward = 40e6, collateral = 1e9, party = "Red Frog"),
            line(3, "courier", "in_progress", 5, acceptedH = 4, reward = 70e6, collateral = 3.2e9),
            line(4, "courier", "outstanding", 2, reward = 50e6, collateral = 2e9, expiresInH = 10, party = null),
            line(5, "item_exchange", "finished", 100, doneH = 90, price = 7e9, title = "PLEX x500"),
            line(6, "item_exchange", "deleted", 200, price = 1e9, title = "Old stuff"),
            line(7, "courier", "finished", 24 * 40, doneH = 24 * 39, reward = 99e6, collateral = 1e9),
        )

    @Test
    fun `totals cover history, the last 30 days and active couriers`() {
        val t = totals(lines, now)

        t.active shouldBe 2
        t.inTransit shouldBe 1
        t.collateralInTransit shouldBe 3.2e9
        t.collateralOutstanding shouldBe 2e9
        // #7 finished 39 days ago: in the totals, not in the 30-day part.
        t.couriers shouldBe 3
        t.rewards shouldBe 199e6
        t.rewards30d shouldBe 100e6
        t.rewardToCollateralPct!! shouldBe (3.98 plusOrMinus 1e-9)
        t.avgDelivery shouldBe Duration.ofMinutes(16 * 60 + 40)
        t.avgAccept shouldBe Duration.ofMinutes(270)
        t.exchange shouldBe 7e9
        t.exchanges shouldBe 1
        t.expiring24h shouldBe 1
    }

    @Test
    fun `lanes and parties group couriers`() {
        lanes(lines, now).single().count shouldBe 5
        parties(lines, now).map { it.label to it.count } shouldBe listOf("Push Industries" to 5, "Red Frog" to 1)
        formatSpan(Duration.ofMinutes(26 * 60 + 5)) shouldBe "1d 2h"
        formatSpan(Duration.ofMinutes(65)) shouldBe "1h 5m"
        statusGroup("finished_issuer") shouldBe StatusGroup.FINISHED
    }

    @Test
    fun `body renders`() {
        DatabaseManager.close()
        DatabaseManager.initialize(":memory:")
        runDesktopComposeUiTest(1440, 760) {
            setContent { MaterialTheme(colorScheme = DarkColorScheme) { Surface { ContractsBody(lines, false, null, now = now) } } }
            waitForIdle()
            onAllNodesWithText("Jita → Amarr", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() shouldBe true
            val out = File("build/contracts-screens").apply { mkdirs() }
            ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", File(out, "contracts.png"))
        }
    }
}
