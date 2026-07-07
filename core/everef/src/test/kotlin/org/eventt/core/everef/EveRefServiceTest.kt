package org.eventt.core.everef

import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.eventt.core.model.MarketHistoryModel
import org.junit.jupiter.api.Test
import java.time.LocalDate

class EveRefServiceTest {
    @Test
    fun `parseFileDate extracts the date from a well-formed filename`() {
        EveRefService.parseFileDate("market-history-2024-01-15.csv.bz2") shouldBe LocalDate.of(2024, 1, 15)
    }

    @Test
    fun `parseFileDate returns null for a filename that doesn't match the pattern`() {
        EveRefService.parseFileDate("readme.txt").shouldBeNull()
    }

    @Test
    fun `parseFileDate returns null for a syntactically matching but invalid calendar date`() {
        EveRefService.parseFileDate("market-history-2024-13-40.csv.bz2").shouldBeNull()
    }

    @Test
    fun `parseLine reads a well-formed CSV row into a MarketHistoryModel`() {
        val line = "34,10000002,2024-01-15,5.5,5.2,5.0,100000,250"

        val result =
            EveRefService.parseLine(
                line,
                typeIdIdx = 0,
                regionIdIdx = 1,
                dateIdx = 2,
                highestIdx = 3,
                averageIdx = 4,
                lowestIdx = 5,
                volumeIdx = 6,
                orderCountIdx = 7,
            )

        result shouldBe
            MarketHistoryModel(
                typeId = 34,
                regionId = 10000002,
                date = "2024-01-15",
                highest = 5.5,
                average = 5.2,
                lowest = 5.0,
                volume = 100_000L,
                orderCount = 250L,
            )
    }

    @Test
    fun `parseLine defaults a missing numeric column to zero instead of failing the whole row`() {
        val line = "34,10000002,2024-01-15,,5.2,5.0,100000,250"

        val result =
            EveRefService.parseLine(
                line,
                typeIdIdx = 0,
                regionIdIdx = 1,
                dateIdx = 2,
                highestIdx = 3,
                averageIdx = 4,
                lowestIdx = 5,
                volumeIdx = 6,
                orderCountIdx = 7,
            )

        result?.highest shouldBe 0.0
        result?.average shouldBe 5.2
    }

    @Test
    fun `parseLine treats a column index that wasn't found in the header (-1) as absent, defaulting to zero`() {
        val line = "34,10000002,2024-01-15,5.5,5.2,5.0,100000"

        val result =
            EveRefService.parseLine(
                line,
                typeIdIdx = 0,
                regionIdIdx = 1,
                dateIdx = 2,
                highestIdx = 3,
                averageIdx = 4,
                lowestIdx = 5,
                volumeIdx = 6,
                orderCountIdx = -1,
            )

        result?.orderCount shouldBe 0L
    }

    @Test
    fun `parseLine returns null when the row is too short to contain the required columns`() {
        val line = "34,10000002"

        val result =
            EveRefService.parseLine(
                line,
                typeIdIdx = 0,
                regionIdIdx = 1,
                dateIdx = 2,
                highestIdx = 3,
                averageIdx = 4,
                lowestIdx = 5,
                volumeIdx = 6,
                orderCountIdx = 7,
            )

        result.shouldBeNull()
    }

    @Test
    fun `parseLine returns null when a required column isn't numeric`() {
        val line = "not-a-number,10000002,2024-01-15,5.5,5.2,5.0,100000,250"

        val result =
            EveRefService.parseLine(
                line,
                typeIdIdx = 0,
                regionIdIdx = 1,
                dateIdx = 2,
                highestIdx = 3,
                averageIdx = 4,
                lowestIdx = 5,
                volumeIdx = 6,
                orderCountIdx = 7,
            )

        result.shouldBeNull()
    }
}
