package org.eventt.core.database

import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test

class DatabaseManagerTest {
    companion object {
        @BeforeAll
        @JvmStatic
        fun initInMemoryDb() {
            DatabaseManager.close()
            DatabaseManager.initialize(":memory:")
        }
    }

    @Test
    fun `vacuumIfNeeded does nothing when the free-page ratio is below the threshold`() {
        StaticDataDao.setSetting("db.last_vacuum_millis", "")

        DatabaseManager.vacuumIfNeeded(minFreeRatio = 1.1, minInterval = 0L)

        // Never actually ran VACUUM, so it never touched the setting past our forced blank value.
        StaticDataDao.getSetting("db.last_vacuum_millis") shouldBe ""
    }

    @Test
    fun `vacuumIfNeeded runs and records the timestamp once both conditions are met`() {
        StaticDataDao.setSetting("db.last_vacuum_millis", "0")
        val before = System.currentTimeMillis()

        DatabaseManager.vacuumIfNeeded(minFreeRatio = 0.0, minInterval = 0L)

        val recorded = StaticDataDao.getSetting("db.last_vacuum_millis").shouldNotBeNull().toLong()
        (recorded >= before) shouldBe true
    }

    @Test
    fun `vacuumIfNeeded skips when the last run was too recent, even if the ratio qualifies`() {
        StaticDataDao.setSetting("db.last_vacuum_millis", System.currentTimeMillis().toString())
        val before = StaticDataDao.getSetting("db.last_vacuum_millis")

        DatabaseManager.vacuumIfNeeded(minFreeRatio = 0.0, minInterval = 24 * 60 * 60 * 1000L)

        StaticDataDao.getSetting("db.last_vacuum_millis") shouldBe before
    }

    @Test
    fun `vacuumIfNeeded treats a missing setting as never having run`() {
        DatabaseManager.transaction { createStatement().use { it.execute("DELETE FROM settings WHERE key = 'db.last_vacuum_millis'") } }
        StaticDataDao.getSetting("db.last_vacuum_millis").shouldBeNull()

        DatabaseManager.vacuumIfNeeded(minFreeRatio = 0.0, minInterval = 0L)

        StaticDataDao.getSetting("db.last_vacuum_millis").shouldNotBeNull()
    }
}
