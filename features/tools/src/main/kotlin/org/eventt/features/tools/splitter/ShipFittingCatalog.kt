package org.eventt.features.tools.splitter

data class HaulerOption(
    val typeId: Int,
    val name: String,
)

data class FittingItem(
    val typeId: Int,
    val quantity: Int,
    val flag: String,
)

/** Hardcoded hauler list + Iteron V cargo-fit simulation, ported verbatim from the original web tool. */
object ShipFittingCatalog {
    val HAULERS =
        listOf(
            HaulerOption(20185, "Charon"),
            HaulerOption(20187, "Obelisk"),
            HaulerOption(20183, "Providence"),
            HaulerOption(20189, "Fenrir"),
            HaulerOption(28844, "Rhea"),
            HaulerOption(28848, "Anshar"),
            HaulerOption(28850, "Ark"),
            HaulerOption(28846, "Nomad"),
            HaulerOption(657, "Iteron Mark V"),
            HaulerOption(649, "Tayra"),
            HaulerOption(29248, "Magnate"),
        )

    private const val ITERON_V_TYPE_ID = 657

    // Simulates a real Iteron V cargo-expanded fit — matches the original web tool's hardcode.
    private val ITERON_V_EXTRA_ITEMS =
        listOf(
            FittingItem(1319, 1, "LoSlot0"),
            FittingItem(1319, 1, "LoSlot1"),
            FittingItem(1319, 1, "LoSlot2"),
            FittingItem(1319, 1, "LoSlot3"),
            FittingItem(1319, 1, "LoSlot4"),
            FittingItem(31125, 1, "RigSlot0"),
            FittingItem(31125, 1, "RigSlot1"),
            FittingItem(31119, 1, "RigSlot2"),
        )

    fun extraFittingItemsFor(shipTypeId: Int): List<FittingItem> = if (shipTypeId == ITERON_V_TYPE_ID) ITERON_V_EXTRA_ITEMS else emptyList()
}
