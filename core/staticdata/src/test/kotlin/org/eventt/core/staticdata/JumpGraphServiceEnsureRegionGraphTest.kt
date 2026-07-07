package org.eventt.core.staticdata

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockkObject
import io.mockk.unmockkObject
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.eventt.core.database.DatabaseManager
import org.eventt.core.database.StaticDataDao
import org.eventt.core.esi.EsiClient
import org.eventt.core.model.StaticSystemModel
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

private const val REGION_ID = 10000002

// Exercises ensureRegionGraph against a real in-memory StaticDataDao (not mocked, unlike
// JumpGraphServiceTest's bfsDistances tests) so the DB round-trip of fetched/edges is genuine;
// only the ESI network call is faked. Every system in the region must have its
// EsiClient.getUniverseSystem(...) call stubbed explicitly — an unstubbed call on a mockkObject
// falls through to the real implementation instead of throwing, which silently made a real
// network call to the real ESI during development of this test (confirmed harmless: it only
// touched this test's in-memory DB, not ~/.eve-trader/eve_trader.db, but avoid it regardless).
class JumpGraphServiceEnsureRegionGraphTest {
    companion object {
        @BeforeAll
        @JvmStatic
        fun initInMemoryDb() {
            DatabaseManager.close()
            DatabaseManager.initialize(":memory:")
        }
    }

    @BeforeEach
    fun setUp() {
        mockkObject(EsiClient)
        StaticDataDao.bulkInsertSystems(
            listOf(StaticSystemModel(30000142, "Jita", REGION_ID), StaticSystemModel(30000144, "Perimeter", REGION_ID)),
        )
    }

    @AfterEach
    fun tearDown() {
        unmockkObject(EsiClient)
        DatabaseManager.transaction {
            createStatement().use {
                it.execute("DELETE FROM static_systems")
                it.execute("DELETE FROM static_system_jumps")
                it.execute("DELETE FROM static_system_jumps_fetched")
            }
        }
    }

    private fun system(stargateIds: List<Int>) = mapOf("stargates" to stargateIds)

    private fun stargate(destinationSystemId: Int) = mapOf("destination" to mapOf("system_id" to destinationSystemId))

    @Test
    fun `fetches stargates for a missing system and records the resulting edges`() =
        runTest {
            every { EsiClient.getUniverseSystem(30000142) } returns system(listOf(50000001))
            every { EsiClient.getUniverseSystem(30000144) } returns system(emptyList())
            every { EsiClient.getUniverseStargate(50000001) } returns stargate(30000144)

            JumpGraphService.ensureRegionGraph(REGION_ID)

            StaticDataDao.isSystemJumpsFetched(30000142) shouldBe true
            val graph = StaticDataDao.getJumpGraph(listOf(30000142, 30000144))
            graph[30000142] shouldContainExactlyInAnyOrder listOf(30000144)
            graph[30000144] shouldContainExactlyInAnyOrder listOf(30000142)
        }

    @Test
    fun `a system that's already fetched is skipped entirely`() =
        runTest {
            StaticDataDao.markSystemJumpsFetched(30000142)
            StaticDataDao.markSystemJumpsFetched(30000144)

            JumpGraphService.ensureRegionGraph(REGION_ID)

            verify(exactly = 0) { EsiClient.getUniverseSystem(any()) }
        }

    @Test
    fun `a system with no stargates is still marked fetched, with no edges`() =
        runTest {
            every { EsiClient.getUniverseSystem(30000142) } returns system(emptyList())
            every { EsiClient.getUniverseSystem(30000144) } returns system(emptyList())

            JumpGraphService.ensureRegionGraph(REGION_ID)

            StaticDataDao.isSystemJumpsFetched(30000142) shouldBe true
            StaticDataDao.isSystemJumpsFetched(30000144) shouldBe true
            StaticDataDao
                .getJumpGraph(listOf(30000142, 30000144))
                .values
                .flatten()
                .shouldBeEmpty()
        }

    @Test
    fun `a system whose ESI call fails stays unfetched, but progress is still reported for it`() =
        runTest {
            every { EsiClient.getUniverseSystem(30000142) } throws RuntimeException("ESI down")
            every { EsiClient.getUniverseSystem(30000144) } returns system(emptyList())

            val progressCalls = mutableListOf<JumpGraphService.Progress>()
            JumpGraphService.ensureRegionGraph(REGION_ID) { progressCalls.add(it) }

            StaticDataDao.isSystemJumpsFetched(30000142) shouldBe false
            StaticDataDao.isSystemJumpsFetched(30000144) shouldBe true
            progressCalls.size shouldBe 2
            progressCalls.map { it.total } shouldBe listOf(2, 2)
        }
}
