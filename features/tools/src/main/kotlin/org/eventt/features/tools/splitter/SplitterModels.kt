package org.eventt.features.tools.splitter

data class SplitConstraints(
    val maxIskValue: Double,
    val maxVolumeM3: Double,
)

enum class SplitAlgorithm { FILL_FIRST, BALANCED }

data class SplitLineItem(
    val typeId: Int,
    val name: String,
    val quantity: Int,
    val unitPrice: Double,
    val unitVolume: Double,
)

data class Split(
    val index: Int,
    val items: List<SplitLineItem>,
) {
    val totalValue: Double get() = items.sumOf { it.quantity * it.unitPrice }
    val totalVolume: Double get() = items.sumOf { it.quantity * it.unitVolume }
    val itemTypeCount: Int get() = items.size
}

data class UnplacedRemainder(
    val typeId: Int,
    val name: String,
    val quantityRemaining: Int,
    val reason: String,
)

data class SplitPlan(
    val algorithm: SplitAlgorithm,
    val splits: List<Split>,
    val unplaced: List<UnplacedRemainder>,
)
