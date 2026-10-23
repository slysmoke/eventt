package org.eventt.core.database

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test

class LeaderboardRankDaoTest {
    companion object {
        @BeforeAll
        @JvmStatic
        fun initInMemoryDb() {
            DatabaseManager.close()
            DatabaseManager.initialize(":memory:")
        }
    }

    @Test
    fun `previous returns the latest earlier day, and a re-record replaces that day`() {
        LeaderboardRankDao.record("2026-10-20", "30d", mapOf("a" to 1, "b" to 2))
        LeaderboardRankDao.record("2026-10-22", "30d", mapOf("a" to 2, "b" to 1, "c" to 3))
        LeaderboardRankDao.record("2026-10-22", "30d", mapOf("a" to 2, "b" to 1))
        LeaderboardRankDao.record("2026-10-23", "7d", mapOf("a" to 9))

        LeaderboardRankDao.previous("2026-10-24", "30d") shouldBe mapOf("a" to 2, "b" to 1)
        LeaderboardRankDao.previous("2026-10-22", "30d") shouldBe mapOf("a" to 1, "b" to 2)
        LeaderboardRankDao.previous("2026-10-20", "30d") shouldBe emptyMap()
    }
}
