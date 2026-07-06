package org.eventt.core.staticdata

import io.kotest.matchers.maps.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockkObject
import io.mockk.unmockkObject
import org.eventt.core.database.StaticDataDao
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

private const val REGION_ID = 10000002

class JumpGraphServiceTest {
    @BeforeEach
    fun setUp() {
        mockkObject(StaticDataDao)
        every { StaticDataDao.getSystemIdsByRegion(REGION_ID) } returns listOf(1, 2, 3, 4, 5)
    }

    @AfterEach
    fun tearDown() {
        unmockkObject(StaticDataDao)
    }

    private fun stubGraph(graph: Map<Int, List<Int>>) {
        every { StaticDataDao.getJumpGraph(any()) } returns graph
    }

    @Test
    fun `the origin system is its own distance-zero neighbor`() {
        stubGraph(emptyMap())

        JumpGraphService.bfsDistances(fromSystemId = 1, regionId = REGION_ID) shouldContainExactly mapOf(1 to 0)
    }

    @Test
    fun `distances increase by one jump per hop along a chain`() {
        stubGraph(mapOf(1 to listOf(2), 2 to listOf(1, 3), 3 to listOf(2, 4), 4 to listOf(3)))

        val distances = JumpGraphService.bfsDistances(fromSystemId = 1, regionId = REGION_ID)

        distances shouldContainExactly mapOf(1 to 0, 2 to 1, 3 to 2, 4 to 3)
    }

    @Test
    fun `the shortest path is used when multiple routes exist`() {
        // 1 -> 2 -> 3 -> 5 (3 jumps) vs 1 -> 4 -> 5 (2 jumps)
        stubGraph(
            mapOf(
                1 to listOf(2, 4),
                2 to listOf(1, 3),
                3 to listOf(2, 5),
                4 to listOf(1, 5),
                5 to listOf(3, 4),
            ),
        )

        JumpGraphService.bfsDistances(fromSystemId = 1, regionId = REGION_ID)[5] shouldBe 2
    }

    @Test
    fun `systems with no path from the origin are simply absent`() {
        // 3 has no edges at all - unreachable from 1
        stubGraph(mapOf(1 to listOf(2), 2 to listOf(1)))

        val distances = JumpGraphService.bfsDistances(fromSystemId = 1, regionId = REGION_ID)

        distances shouldContainExactly mapOf(1 to 0, 2 to 1)
    }

    @Test
    fun `a cycle in the graph does not cause infinite looping or wrong distances`() {
        stubGraph(mapOf(1 to listOf(2), 2 to listOf(1, 3), 3 to listOf(2, 1)))

        val distances = JumpGraphService.bfsDistances(fromSystemId = 1, regionId = REGION_ID)

        distances shouldContainExactly mapOf(1 to 0, 2 to 1, 3 to 2)
    }
}
