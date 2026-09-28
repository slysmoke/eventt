package org.eventt.features.market

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class StationPresetsTest {
    @Test
    fun `presets survive a save and load, including a region-wide one`() {
        val presets = listOf(StationPreset("Jita 4-4", 10000002, 60003760L), StationPreset("Amarr | hub", 10000043, null))
        decodeStationPresets(encodeStationPresets(presets)) shouldBe
            listOf(StationPreset("Jita 4-4", 10000002, 60003760L), StationPreset("Amarr   hub", 10000043, null))
    }

    @Test
    fun `malformed lines are skipped`() {
        decodeStationPresets("broken\nok|10000002|60003760") shouldBe listOf(StationPreset("ok", 10000002, 60003760L))
    }
}
