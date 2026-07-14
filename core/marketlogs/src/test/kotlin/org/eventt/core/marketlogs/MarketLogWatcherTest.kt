package org.eventt.core.marketlogs

import app.cash.turbine.test
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

// Fixtures transcribed verbatim from real EVE Marketlogs export files (same content as
// MarketLogParserTest — kept local here since a watcher test cares about whole-file behavior,
// not just row parsing).
private const val ORDER_BOOK_CONTENT =
    "price,volRemaining,typeID,range,orderID,volEntered,minVolume,bid,issueDate,duration,stationID,regionID,solarSystemID,jumps,\n" +
        "4690000.0,1500.0,44992,32767,7374815516,1500,1,False,2026-07-09 02:25:23.000,14,60008494,19000001,30002187,11,\n"

private const val MY_ORDERS_CONTENT =
    "orderID,typeID,charID,charName,regionID,regionName,solarSystemID,solarSystemName,stationID,stationName," +
        "range,bid,price,volEntered,volRemaining,minVolume,issueDate,orderState,duration,escrow,isCorp," +
        "accountID,accountOwnerID,accountKey,\n" +
        "7372477240,33904,2113351129,Sasha Winston,10000002,The Forge,30000142,Jita,60003760," +
        "Jita IV - Moon 4 - Caldari Navy Assembly Plant,-1,True,310500000.0,2,2.0,1,2026-07-09 02:57:41.000,0," +
        "90,621000000.0,False,78024504,2113351129,1000,\n"

private const val UNRELATED_CONTENT = "just,some,random,file\nnot,a,marketlog,export\n"

class MarketLogWatcherTest {
    @TempDir
    lateinit var tempDir: File

    @AfterEach
    fun cleanUp() {
        MarketLogWatcher.stop() // also clears the internal "seen" stamp map between tests
    }

    @Test
    fun `a My Orders file is no longer recognized — left alone like any other unrelated file`() =
        runTest {
            val file = File(tempDir, "My Orders-2026.07.09 0311.txt").apply { writeText(MY_ORDERS_CONTENT) }

            MarketLogWatcher.pollOnce(tempDir)
            MarketLogWatcher.pollOnce(tempDir)

            file.exists() shouldBe true
        }

    @Test
    fun `an order book file is split into buy and sell rows and deleted`() =
        runTest {
            val file = File(tempDir, "The Forge-PLEX-2026.07.09 031048.txt").apply { writeText(ORDER_BOOK_CONTENT) }

            MarketLogWatcher.events.test {
                MarketLogWatcher.pollOnce(tempDir)
                MarketLogWatcher.pollOnce(tempDir)

                val event = awaitItem() as MarketLogEvent.OrderBookImported
                event.typeId shouldBe 44992
                event.regionId shouldBe 19000001
                event.sellRows.size shouldBe 1
                event.buyRows.size shouldBe 0

                file.exists() shouldBe false
            }
        }

    @Test
    fun `an unrelated txt file is left alone — no event, not deleted`() =
        runTest {
            val file = File(tempDir, "notes.txt").apply { writeText(UNRELATED_CONTENT) }

            MarketLogWatcher.pollOnce(tempDir)
            MarketLogWatcher.pollOnce(tempDir)

            file.exists() shouldBe true
        }

    @Test
    fun `pollOnce with no directory configured is a no-op`() {
        MarketLogWatcher.pollOnce(null)
    }
}
