package org.eventt.features.tools.splitter

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import kotlin.math.ceil

private const val MAX_VALUE = 3_500_000_000.0
private const val MAX_VOLUME = 320_000.0

private fun item(
    name: String,
    quantity: Int,
    price: Double,
    volume: Double,
    typeId: Int = name.hashCode(),
) = SplitLineItem(typeId, name, quantity, price, volume)

// Fixtures transcribed verbatim from github.com/slysmoke/splitter's test.js reference implementation.
private object Fixtures {
    // Volume-bound: min 10 splits.
    val mixedShipsAndOzone =
        listOf(
            item("Exequror Navy Issue", 20, 41_000_000.0, 10_000.0),
            item("Osprey Navy Issue", 20, 43_820_000.0, 10_000.0),
            item("Zealot", 15, 130_900_000.0, 10_000.0),
            item("Sacrilege", 8, 154_500_000.0, 10_000.0),
            item("Ashimmu", 4, 237_900_000.0, 10_000.0),
            item("Orthrus", 4, 244_400_000.0, 10_000.0),
            item("Phantasm", 5, 233_400_000.0, 10_000.0),
            item("Stratios", 12, 312_800_000.0, 10_000.0),
            item("Gila", 28, 197_300_000.0, 10_000.0),
            item("Liquid Ozone", 2_000_000, 95.05, 0.9),
        )

    // Volume-bound: min 6 splits.
    val battleshipsAndOzone =
        listOf(
            item("Vindicator", 20, 800_000_000.0, 50_000.0),
            item("Liquid Ozone", 1_000_000, 95.0, 0.9),
        )

    // ISK-bound: min 5 splits.
    val uniformT2Cruisers =
        listOf(
            item("Cerberus", 15, 250_000_000.0, 10_000.0),
            item("Eagle", 15, 220_000_000.0, 10_000.0),
            item("Sacrilege", 20, 155_000_000.0, 10_000.0),
            item("Ishtar", 20, 170_000_000.0, 10_000.0),
            item("Vagabond", 15, 200_000_000.0, 10_000.0),
        )

    // ISK-bound: min 11 splits.
    val shipsAndDeadspaceModules =
        listOf(
            item("Exequror Navy Issue", 20, 41_000_000.0, 10_000.0),
            item("Osprey Navy Issue", 20, 43_820_000.0, 10_000.0),
            item("Zealot", 15, 130_900_000.0, 10_000.0),
            item("Sacrilege", 8, 154_500_000.0, 10_000.0),
            item("Ashimmu", 4, 237_900_000.0, 10_000.0),
            item("Orthrus", 4, 244_400_000.0, 10_000.0),
            item("Phantasm", 5, 233_400_000.0, 10_000.0),
            item("Stratios", 12, 312_800_000.0, 10_000.0),
            item("Gila", 28, 197_300_000.0, 10_000.0),
            item("Pithum A-Type Medium Shield Booster", 30, 390_000_000.0, 5.0),
            item("Pithum A-Type Explosive Shield Amp", 40, 1_924_000.0, 5.0),
            item("Pith A-Type X-Large Shield Booster", 20, 243_400_000.0, 50.0),
            item("Astero", 10, 96_000_000.0, 2_500.0),
            item("Dramiel", 3, 55_570_000.0, 2_500.0),
            item("Worm", 12, 57_970_000.0, 2_500.0),
            item("Caldari Navy Hookbill", 25, 10_000_000.0, 2_500.0),
            item("Federation Navy Comet", 25, 8_663_000.0, 2_500.0),
        )

    // 147 item types — exercises the 250-item-type-per-split cap and mixed ISK/volume densities.
    val largeRealisticHaul: List<SplitLineItem> =
        buildList {
            // T1 Battleships (50 000 m3, 150-300M ISK)
            add(item("Raven", 5, 185_000_000.0, 50_000.0))
            add(item("Megathron", 5, 195_000_000.0, 50_000.0))
            add(item("Abaddon", 5, 205_000_000.0, 50_000.0))
            add(item("Tempest", 5, 185_000_000.0, 50_000.0))
            add(item("Scorpion", 3, 180_000_000.0, 50_000.0))
            add(item("Dominix", 4, 175_000_000.0, 50_000.0))
            add(item("Apocalypse", 4, 200_000_000.0, 50_000.0))
            add(item("Typhoon", 3, 185_000_000.0, 50_000.0))
            // Faction Battleships (50 000 m3, 500M-2B ISK)
            add(item("Vindicator", 3, 800_000_000.0, 50_000.0))
            add(item("Nightmare", 2, 1_200_000_000.0, 50_000.0))
            add(item("Machariel", 3, 500_000_000.0, 50_000.0))
            add(item("Bhaalgorn", 2, 1_500_000_000.0, 50_000.0))
            // T2 Battleships (50 000 m3, 1.5-2.5B ISK)
            add(item("Golem", 1, 1_800_000_000.0, 50_000.0))
            add(item("Kronos", 1, 2_000_000_000.0, 50_000.0))
            add(item("Paladin", 1, 2_100_000_000.0, 50_000.0))
            add(item("Vargur", 1, 1_900_000_000.0, 50_000.0))
            // T1 Battlecruisers (15 000 m3, 40-80M ISK)
            add(item("Hurricane", 10, 48_000_000.0, 15_000.0))
            add(item("Drake", 10, 52_000_000.0, 15_000.0))
            add(item("Brutix", 8, 46_000_000.0, 15_000.0))
            add(item("Prophecy", 8, 50_000_000.0, 15_000.0))
            add(item("Ferox", 8, 48_000_000.0, 15_000.0))
            add(item("Myrmidon", 8, 47_000_000.0, 15_000.0))
            // T2 Battlecruisers (15 000 m3, 300-450M ISK)
            add(item("Sleipnir", 3, 380_000_000.0, 15_000.0))
            add(item("Vulture", 3, 350_000_000.0, 15_000.0))
            add(item("Astarte", 2, 410_000_000.0, 15_000.0))
            add(item("Eos", 2, 390_000_000.0, 15_000.0))
            // Faction Battlecruisers (15 000 m3, 230-300M ISK)
            add(item("Hurricane Fleet Issue", 5, 280_000_000.0, 15_000.0))
            add(item("Drake Navy Issue", 5, 270_000_000.0, 15_000.0))
            add(item("Brutix Navy Issue", 5, 255_000_000.0, 15_000.0))
            // T2 Cruisers - HAC (10 000 m3, 130-320M ISK)
            add(item("Zealot", 6, 131_000_000.0, 10_000.0))
            add(item("Sacrilege", 5, 155_000_000.0, 10_000.0))
            add(item("Eagle", 5, 220_000_000.0, 10_000.0))
            add(item("Cerberus", 5, 250_000_000.0, 10_000.0))
            add(item("Vagabond", 5, 200_000_000.0, 10_000.0))
            add(item("Muninn", 5, 150_000_000.0, 10_000.0))
            add(item("Ishtar", 6, 170_000_000.0, 10_000.0))
            add(item("Deimos", 5, 165_000_000.0, 10_000.0))
            // T2 Cruisers - Recon (10 000 m3, 250-310M ISK)
            add(item("Pilgrim", 3, 280_000_000.0, 10_000.0))
            add(item("Curse", 3, 305_000_000.0, 10_000.0))
            add(item("Huginn", 3, 255_000_000.0, 10_000.0))
            add(item("Rapier", 3, 285_000_000.0, 10_000.0))
            // T2 Cruisers - Logistics (10 000 m3, 180-210M ISK)
            add(item("Basilisk", 4, 200_000_000.0, 10_000.0))
            add(item("Scimitar", 4, 185_000_000.0, 10_000.0))
            add(item("Guardian", 4, 195_000_000.0, 10_000.0))
            add(item("Oneiros", 4, 190_000_000.0, 10_000.0))
            // Faction Cruisers (10 000 m3, 190-360M ISK)
            add(item("Stratios", 4, 313_000_000.0, 10_000.0))
            add(item("Gila", 6, 197_000_000.0, 10_000.0))
            add(item("Orthrus", 3, 244_000_000.0, 10_000.0))
            add(item("Vigilant", 3, 305_000_000.0, 10_000.0))
            add(item("Cynabal", 3, 355_000_000.0, 10_000.0))
            add(item("Ashimmu", 3, 238_000_000.0, 10_000.0))
            add(item("Phantasm", 3, 233_000_000.0, 10_000.0))
            // T3 Cruisers (10 000 m3, 280-450M ISK)
            add(item("Tengu", 4, 380_000_000.0, 10_000.0))
            add(item("Loki", 4, 350_000_000.0, 10_000.0))
            add(item("Legion", 4, 360_000_000.0, 10_000.0))
            add(item("Proteus", 4, 320_000_000.0, 10_000.0))
            // T1 Cruisers (10 000 m3, 7-12M ISK)
            add(item("Caracal", 20, 7_500_000.0, 10_000.0))
            add(item("Thorax", 20, 8_000_000.0, 10_000.0))
            add(item("Rupture", 20, 7_000_000.0, 10_000.0))
            add(item("Maller", 20, 8_500_000.0, 10_000.0))
            add(item("Vexor", 20, 9_000_000.0, 10_000.0))
            // T2 Frigates (2 500 m3, 15-40M ISK)
            add(item("Harpy", 10, 22_000_000.0, 2_500.0))
            add(item("Hawk", 10, 18_000_000.0, 2_500.0))
            add(item("Vengeance", 10, 25_000_000.0, 2_500.0))
            add(item("Retribution", 10, 28_000_000.0, 2_500.0))
            add(item("Jaguar", 10, 30_000_000.0, 2_500.0))
            add(item("Wolf", 10, 32_000_000.0, 2_500.0))
            add(item("Enyo", 10, 20_000_000.0, 2_500.0))
            add(item("Ishkur", 10, 24_000_000.0, 2_500.0))
            add(item("Taranis", 10, 22_000_000.0, 2_500.0))
            add(item("Ares", 10, 16_000_000.0, 2_500.0))
            add(item("Stiletto", 10, 18_000_000.0, 2_500.0))
            add(item("Crow", 10, 20_000_000.0, 2_500.0))
            // Faction Frigates (2 500 m3, 50-130M ISK)
            add(item("Daredevil", 5, 120_000_000.0, 2_500.0))
            add(item("Dramiel", 5, 55_000_000.0, 2_500.0))
            add(item("Worm", 10, 58_000_000.0, 2_500.0))
            add(item("Succubus", 5, 90_000_000.0, 2_500.0))
            add(item("Astero", 8, 96_000_000.0, 2_500.0))
            // T1 Destroyers (5 000 m3, 1.5-3M ISK)
            add(item("Catalyst", 30, 1_500_000.0, 5_000.0))
            add(item("Thrasher", 30, 1_600_000.0, 5_000.0))
            add(item("Cormorant", 20, 2_000_000.0, 5_000.0))
            add(item("Coercer", 20, 2_000_000.0, 5_000.0))
            // T2 Destroyers / Interdictors (5 000 m3, 45-65M ISK)
            add(item("Heretic", 5, 45_000_000.0, 5_000.0))
            add(item("Flycatcher", 5, 50_000_000.0, 5_000.0))
            add(item("Eris", 5, 55_000_000.0, 5_000.0))
            add(item("Sabre", 5, 42_000_000.0, 5_000.0))
            // Deadspace / Faction Modules (5-50 m3, 50M-500M ISK)
            add(item("Pithum A-Type Med Shield Booster", 10, 390_000_000.0, 5.0))
            add(item("Pith A-Type XL Shield Booster", 8, 243_000_000.0, 50.0))
            add(item("Gist X-Type 500MN MWD", 5, 420_000_000.0, 10.0))
            add(item("Gist A-Type Large Shield Booster", 6, 280_000_000.0, 10.0))
            add(item("Republic Fleet Gyrostabilizer", 15, 85_000_000.0, 5.0))
            add(item("Shadow Serpentis Stasis Web", 8, 150_000_000.0, 5.0))
            add(item("Federation Navy Web", 10, 95_000_000.0, 5.0))
            add(item("Caldari Navy BCU", 12, 55_000_000.0, 5.0))
            add(item("Imperial Navy Heat Sink", 12, 48_000_000.0, 5.0))
            // T2 Modules (5-25 m3, 1-20M ISK)
            add(item("Damage Control II", 50, 2_000_000.0, 5.0))
            add(item("Warp Disruptor II", 50, 1_200_000.0, 5.0))
            add(item("Warp Scrambler II", 50, 900_000.0, 5.0))
            add(item("Stasis Webifier II", 50, 1_500_000.0, 5.0))
            add(item("1MN Afterburner II", 50, 500_000.0, 5.0))
            add(item("10MN Afterburner II", 40, 2_000_000.0, 5.0))
            add(item("50MN Microwarpdrive II", 30, 6_000_000.0, 10.0))
            add(item("500MN Microwarpdrive II", 15, 18_000_000.0, 10.0))
            add(item("Medium Shield Extender II", 50, 350_000.0, 10.0))
            add(item("Large Shield Extender II", 30, 700_000.0, 10.0))
            add(item("Energized Adaptive Nano Membrane II", 40, 4_500_000.0, 5.0))
            add(item("Adaptive Invulnerability Field II", 30, 3_800_000.0, 10.0))
            add(item("Heavy Pulse Laser II", 60, 5_500_000.0, 10.0))
            add(item("Heavy Neutron Blaster II", 60, 6_000_000.0, 10.0))
            add(item("425mm AutoCannon II", 60, 4_800_000.0, 10.0))
            add(item("Medium Armor Repairer II", 40, 1_800_000.0, 10.0))
            add(item("Large Armor Repairer II", 20, 4_000_000.0, 10.0))
            add(item("Capacitor Power Relay II", 40, 800_000.0, 5.0))
            add(item("Power Diagnostic System II", 40, 600_000.0, 5.0))
            add(item("Co-Processor II", 40, 700_000.0, 5.0))
            // Rigs - Small (2 m3, 0.3-3M ISK)
            add(item("Small Trimark Armor Pump I", 100, 400_000.0, 2.0))
            add(item("Small Core Defense Field Extender I", 100, 300_000.0, 2.0))
            add(item("Small Ancillary Current Router I", 80, 500_000.0, 2.0))
            add(item("Small Anti-Explosive Screen Reinf I", 80, 350_000.0, 2.0))
            add(item("Small Transverse Bulkhead I", 60, 600_000.0, 2.0))
            add(item("Small Explosive Armor Reinforcer I", 60, 250_000.0, 2.0))
            // Rigs - Medium (5 m3, 1-15M ISK)
            add(item("Medium Trimark Armor Pump I", 50, 2_500_000.0, 5.0))
            add(item("Medium Core Defense Field Extender I", 50, 1_800_000.0, 5.0))
            add(item("Medium Ancillary Current Router I", 40, 2_000_000.0, 5.0))
            add(item("Medium Anti-Explosive Screen Reinf I", 40, 1_500_000.0, 5.0))
            add(item("Medium Capacitor Control Circuit I", 30, 3_500_000.0, 5.0))
            add(item("Medium Semiconductor Memory Cell I", 30, 4_000_000.0, 5.0))
            // Rigs - Large (40 m3, 5-80M ISK)
            add(item("Large Trimark Armor Pump I", 20, 4_000_000.0, 40.0))
            add(item("Large Core Defense Field Extender I", 20, 5_000_000.0, 40.0))
            add(item("Large Ancillary Current Router I", 15, 8_000_000.0, 40.0))
            add(item("Large Anti-Explosive Screen Reinf I", 15, 4_500_000.0, 40.0))
            add(item("Large Capacitor Control Circuit I", 10, 12_000_000.0, 40.0))
            // Drones - Light (5 m3, 0.1-2M ISK)
            add(item("Hobgoblin II", 200, 200_000.0, 5.0))
            add(item("Acolyte II", 200, 200_000.0, 5.0))
            add(item("Warrior II", 200, 180_000.0, 5.0))
            add(item("Hornet EC-300", 100, 150_000.0, 5.0))
            // Drones - Medium (10 m3, 0.5-5M ISK)
            add(item("Hammerhead II", 100, 550_000.0, 10.0))
            add(item("Infiltrator II", 100, 600_000.0, 10.0))
            add(item("Vespa EC-600", 80, 450_000.0, 10.0))
            add(item("Valkyrie II", 80, 500_000.0, 10.0))
            // Drones - Heavy (25 m3, 1.5-20M ISK)
            add(item("Ogre II", 50, 1_500_000.0, 25.0))
            add(item("Bouncer II", 30, 2_200_000.0, 25.0))
            add(item("Berserker II", 50, 1_800_000.0, 25.0))
            // Sentry Drones (50 m3, 7-15M ISK)
            add(item("Garde II", 30, 8_000_000.0, 50.0))
            add(item("Curator II", 30, 10_000_000.0, 50.0))
            add(item("Warden II", 30, 9_000_000.0, 50.0))
            add(item("Wasp II", 30, 12_000_000.0, 50.0))
        }
}

/** Kotlin port of test.js's verify(): every split obeys both caps and the 250-type limit, and
 *  every original item's quantity is fully accounted for across splits + unplaced. */
private fun verify(
    plan: SplitPlan,
    original: List<SplitLineItem>,
) {
    plan.splits.forEach { split ->
        (split.totalValue <= MAX_VALUE * 1.0001) shouldBe true
        (split.totalVolume <= MAX_VOLUME * 1.0001) shouldBe true
        (split.itemTypeCount <= SplitterService.MAX_ITEM_TYPES_PER_SPLIT) shouldBe true
    }

    val placedByName = mutableMapOf<String, Int>()
    plan.splits.forEach { split -> split.items.forEach { placedByName[it.name] = (placedByName[it.name] ?: 0) + it.quantity } }
    val unplacedByName = plan.unplaced.associate { it.name to it.quantityRemaining }

    original.forEach { orig ->
        val got = (placedByName[orig.name] ?: 0) + (unplacedByName[orig.name] ?: 0)
        got shouldBe orig.quantity
    }
}

class SplitterServiceTest {
    private val allFixtures =
        listOf(
            "Mixed ships + Liquid Ozone" to Fixtures.mixedShipsAndOzone,
            "Faction battleships + Liquid Ozone" to Fixtures.battleshipsAndOzone,
            "Uniform T2 cruisers" to Fixtures.uniformT2Cruisers,
            "Ships + deadspace modules" to Fixtures.shipsAndDeadspaceModules,
            "Large realistic haul (147 types)" to Fixtures.largeRealisticHaul,
        )

    @Test
    fun `every fixture's Fill First plan obeys caps and accounts for all quantity`() {
        allFixtures.forEach { (_, items) ->
            val plan = SplitterService.fillFirst(items, SplitConstraints(MAX_VALUE, MAX_VOLUME))
            verify(plan, items)
        }
    }

    @Test
    fun `every fixture's Balanced plan obeys caps and accounts for all quantity`() {
        allFixtures.forEach { (_, items) ->
            val plan = SplitterService.balanced(items, SplitConstraints(MAX_VALUE, MAX_VOLUME))
            verify(plan, items)
        }
    }

    @Test
    fun `mixed ships and ozone fixture is volume-bound with a theoretical minimum of 10 splits`() {
        val items = Fixtures.mixedShipsAndOzone
        val totalVolume = items.sumOf { it.quantity * it.unitVolume }
        val totalValue = items.sumOf { it.quantity * it.unitPrice }
        val minSplits = maxOf(ceil(totalVolume / MAX_VOLUME).toInt(), ceil(totalValue / MAX_VALUE).toInt())
        minSplits shouldBe 10

        val ffd = SplitterService.fillFirst(items, SplitConstraints(MAX_VALUE, MAX_VOLUME))
        ffd.splits.size shouldBe 10
    }

    @Test
    fun `uniform T2 cruisers fixture is ISK-bound with a theoretical minimum of 5 splits`() {
        val items = Fixtures.uniformT2Cruisers
        val totalValue = items.sumOf { it.quantity * it.unitPrice }
        val minSplits = ceil(totalValue / MAX_VALUE).toInt()
        minSplits shouldBe 5

        val ffd = SplitterService.fillFirst(items, SplitConstraints(MAX_VALUE, MAX_VOLUME))
        ffd.splits.size shouldBe 5
    }

    @Test
    fun `recommend picks whichever algorithm produced fewer splits`() {
        val items = Fixtures.mixedShipsAndOzone
        val (ffd, balanced) = SplitterService.computeBoth(items, SplitConstraints(MAX_VALUE, MAX_VOLUME))
        val recommended = SplitterService.recommend(ffd, balanced)
        val expected = if (balanced.splits.size < ffd.splits.size) SplitAlgorithm.BALANCED else SplitAlgorithm.FILL_FIRST
        recommended shouldBe expected
    }

    @Test
    fun `zero price or zero volume makes an item unconstrained by that dimension`() {
        val items =
            listOf(
                item("Free Sample", 500, 0.0, 0.0, typeId = 1),
                item("Filler", 1, 1_000_000.0, 100.0, typeId = 2),
            )
        val plan = SplitterService.fillFirst(items, SplitConstraints(MAX_VALUE, MAX_VOLUME))
        // The zero-priced/zero-volumed item should dump its full quantity into a single split.
        plan.splits.any { split -> split.items.any { it.name == "Free Sample" && it.quantity == 500 } } shouldBe true
        plan.unplaced.isEmpty() shouldBe true
    }

    @Test
    fun `an item whose single unit alone exceeds a cap is reported unplaced, not silently dropped`() {
        val oversized = item("Titan", 2, MAX_VALUE * 2, 1_000_000.0, typeId = 99)
        val normal = item("Frigate", 5, 1_000_000.0, 100.0, typeId = 100)

        val ffdPlan = SplitterService.fillFirst(listOf(oversized, normal), SplitConstraints(MAX_VALUE, MAX_VOLUME))
        ffdPlan.unplaced.single().let {
            it.name shouldBe "Titan"
            it.quantityRemaining shouldBe 2
        }
        ffdPlan.splits.flatMap { it.items }.none { it.name == "Titan" } shouldBe true

        val balancedPlan = SplitterService.balanced(listOf(oversized, normal), SplitConstraints(MAX_VALUE, MAX_VOLUME))
        balancedPlan.unplaced.single().name shouldBe "Titan"
    }

    @Test
    fun `no split exceeds the 250 item-type cap even with many distinct types`() {
        val manyItems = (1..400).map { item("Item$it", 1, 1.0, 0.001, typeId = it) }
        val plan = SplitterService.fillFirst(manyItems, SplitConstraints(MAX_VALUE, MAX_VOLUME))
        plan.splits.forEach { (it.itemTypeCount <= SplitterService.MAX_ITEM_TYPES_PER_SPLIT) shouldBe true }
        verify(plan, manyItems)
    }
}
