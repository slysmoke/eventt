package org.eventt.features.market

import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.should
import io.kotest.matchers.shouldBe
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test

private const val TOLERANCE = 0.0001

class Adam4EveFlowServiceTest {
    @Test
    fun `sums amount per location+type, split by is_buy_order`() {
        val body =
            """
            location_id;region_id;type_id;is_buy_order;has_gone;scanDate;amount;high;low;avg;orderNum;iskValue
            60003760;10000002;34;0;0;2026-09-15;2908965487;4.06;4.02;3.98;86;11753391193
            60003760;10000002;34;1;0;2026-09-15;520651762;4.00;3.63;3.95;63;2057196460
            60000004;10000033;35659;0;0;2026-09-15;1;99060.00;99060.00;99060.00;1;99060
            """.trimIndent()

        val result = Adam4EveFlowService.parseCsv(body)

        result[60003760L to 34] shouldBe StationFlow(buyAmount = 520651762.0, sellAmount = 2908965487.0)
        result[60000004L to 35659] shouldBe StationFlow(buyAmount = 0.0, sellAmount = 1.0)
        result.size shouldBe 2
    }

    @Test
    fun `accumulates duplicate rows for the same location+type+side instead of overwriting`() {
        val body =
            """
            location_id;region_id;type_id;is_buy_order;has_gone;scanDate;amount;high;low;avg;orderNum;iskValue
            60003760;10000002;34;0;0;2026-09-15;100;4.0;4.0;4.0;1;400
            60003760;10000002;34;0;0;2026-09-15;50;4.0;4.0;4.0;1;200
            """.trimIndent()

        Adam4EveFlowService.parseCsv(body)[60003760L to 34] shouldBe StationFlow(buyAmount = 0.0, sellAmount = 150.0)
    }

    @Test
    fun `skips malformed rows instead of throwing`() {
        val body =
            """
            location_id;region_id;type_id;is_buy_order;has_gone;scanDate;amount;high;low;avg;orderNum;iskValue
            not,enough,columns
            60003760;10000002;34;0;0;2026-09-15;100;4.0;4.0;4.0;1;400
            """.trimIndent()

        Adam4EveFlowService.parseCsv(body) shouldBe mapOf(60003760L to 34 to StationFlow(buyAmount = 0.0, sellAmount = 100.0))
    }
}

class Adam4EveFlowServiceNetworkTest {
    private val server = MockWebServer()

    @AfterEach
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `averages over the days actually fetched, skipping gaps`() {
        Adam4EveFlowService.resetCacheForTesting()
        server.start()
        Adam4EveFlowService.baseUrl = server.url("/MarketOrdersTrades").toString().trimEnd('/')

        fun csvRow(amount: Long) =
            "location_id;region_id;type_id;is_buy_order;has_gone;scanDate;amount;high;low;avg;orderNum;iskValue\n" +
                "60003760;10000002;34;0;0;2026-01-01;$amount;4.0;4.0;4.0;1;${amount * 4}\n"

        // 7-day window: 2 days 404 (weekend gaps), 5 days return data -- average must divide by
        // the 5 successful fetches, not by 7.
        server.enqueue(MockResponse().setResponseCode(404))
        server.enqueue(MockResponse().setBody(csvRow(100)))
        server.enqueue(MockResponse().setBody(csvRow(200)))
        server.enqueue(MockResponse().setResponseCode(404))
        server.enqueue(MockResponse().setBody(csvRow(300)))
        server.enqueue(MockResponse().setBody(csvRow(400)))
        server.enqueue(MockResponse().setBody(csvRow(500)))

        kotlinx.coroutines.runBlocking {
            val result = Adam4EveFlowService.fetchStationFlow(60003760L, listOf(34))
            // (100+200+300+400+500) / 5 = 300
            result[34]!!.sellAmount should (300.0 plusOrMinus TOLERANCE)
        }
    }

    // Regression: a capital-tier item can trade as rarely as "1 unit in the whole window" -- real,
    // verifiable in-game activity that integer-averaging used to floor to a flat 0 (indistinguishable
    // from "no data at all"). The average must stay fractional, not collapse thin-but-real activity.
    @Test
    fun `a single trade across the window averages to a fraction, not zero`() {
        Adam4EveFlowService.resetCacheForTesting()
        server.start()
        Adam4EveFlowService.baseUrl = server.url("/MarketOrdersTrades").toString().trimEnd('/')

        val oneTradeDay =
            "location_id;region_id;type_id;is_buy_order;has_gone;scanDate;amount;high;low;avg;orderNum;iskValue\n" +
                "60003760;10000002;34;0;0;2026-01-01;1;4.0;4.0;4.0;1;4\n"
        val noTradeDay = "location_id;region_id;type_id;is_buy_order;has_gone;scanDate;amount;high;low;avg;orderNum;iskValue\n"

        server.enqueue(MockResponse().setBody(oneTradeDay))
        repeat(4) { server.enqueue(MockResponse().setBody(noTradeDay)) }

        kotlinx.coroutines.runBlocking {
            val result = Adam4EveFlowService.fetchStationFlow(60003760L, listOf(34))
            // 1 unit / 5 fetched days = 0.2 -- a real, nonzero signal, not 0.
            result[34]!!.sellAmount should (0.2 plusOrMinus TOLERANCE)
        }
    }
}
