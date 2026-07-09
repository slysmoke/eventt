package org.eventt.features.tools.splitter

import kotlin.math.ceil

/**
 * Cargo Splitter algorithms — Kotlin port of the "Fill First" (FFD) and "Balanced" bin-packing
 * algorithms from the original web tool (github.com/slysmoke/splitter), transliterated from its
 * test.js reference implementation. One deliberate behavior change: an item whose single unit
 * alone exceeds a constraint can never fit in any split — the original silently dropped it from
 * the output with no warning; here it's collected in SplitPlan.unplaced instead.
 */
object SplitterService {
    const val MAX_ITEM_TYPES_PER_SPLIT = 250 // ESI's fitting-item hard limit, non-configurable

    fun computeBoth(
        items: List<SplitLineItem>,
        constraints: SplitConstraints,
    ): Pair<SplitPlan, SplitPlan> = fillFirst(items, constraints) to balanced(items, constraints)

    fun recommend(
        ffd: SplitPlan,
        balanced: SplitPlan,
    ): SplitAlgorithm = if (balanced.splits.size < ffd.splits.size) SplitAlgorithm.BALANCED else SplitAlgorithm.FILL_FIRST

    private fun exceedsCapsAlone(
        item: SplitLineItem,
        c: SplitConstraints,
    ): Boolean = item.unitPrice > c.maxIskValue || item.unitVolume > c.maxVolumeM3

    fun fillFirst(
        items: List<SplitLineItem>,
        c: SplitConstraints,
    ): SplitPlan {
        data class Remaining(
            val item: SplitLineItem,
            var qty: Int,
        )

        val unplaced = mutableListOf<UnplacedRemainder>()
        val remaining =
            items
                .filter { it.quantity > 0 }
                .filterNot { item ->
                    val stuck = exceedsCapsAlone(item, c)
                    if (stuck) {
                        unplaced += UnplacedRemainder(item.typeId, item.name, item.quantity, "single unit exceeds max ISK/volume per split")
                    }
                    stuck
                }.sortedByDescending { it.unitPrice }
                .map { Remaining(it, it.quantity) }
                .toMutableList()

        val splits = mutableListOf<Split>()

        while (remaining.any { it.qty > 0 }) {
            val bucket = mutableListOf<SplitLineItem>()
            var valueLeft = c.maxIskValue
            var volumeLeft = c.maxVolumeM3

            for (r in remaining) {
                if (r.qty <= 0 || bucket.size >= MAX_ITEM_TYPES_PER_SPLIT) continue

                val byValue = if (r.item.unitPrice > 0) (valueLeft / r.item.unitPrice).toInt() else r.qty
                val byVolume = if (r.item.unitVolume > 0) (volumeLeft / r.item.unitVolume).toInt() else r.qty
                val take = minOf(r.qty, byValue, byVolume)
                if (take <= 0) continue

                bucket += r.item.copy(quantity = take)
                r.qty -= take
                if (r.item.unitPrice > 0) valueLeft -= take * r.item.unitPrice
                if (r.item.unitVolume > 0) volumeLeft -= take * r.item.unitVolume
            }

            if (bucket.isEmpty()) break // safety valve — a full pass placed nothing more
            splits += Split(splits.size + 1, bucket)
        }

        remaining.filter { it.qty > 0 }.forEach {
            unplaced += UnplacedRemainder(it.item.typeId, it.item.name, it.qty, "could not fit in any split")
        }

        return SplitPlan(SplitAlgorithm.FILL_FIRST, splits, unplaced)
    }

    fun balanced(
        items: List<SplitLineItem>,
        c: SplitConstraints,
    ): SplitPlan {
        val unplaced = mutableListOf<UnplacedRemainder>()
        val sorted =
            items
                .filter { it.quantity > 0 }
                .filterNot { item ->
                    val stuck = exceedsCapsAlone(item, c)
                    if (stuck) {
                        unplaced += UnplacedRemainder(item.typeId, item.name, item.quantity, "single unit exceeds max ISK/volume per split")
                    }
                    stuck
                }.sortedByDescending { if (it.unitVolume > 0) it.unitPrice / it.unitVolume else Double.MAX_VALUE }

        if (sorted.isEmpty()) return SplitPlan(SplitAlgorithm.BALANCED, emptyList(), unplaced)

        val totalVolume = sorted.sumOf { it.quantity * it.unitVolume }
        val totalValue = sorted.sumOf { it.quantity * it.unitPrice }
        val preAlloc =
            maxOf(
                ceil(totalVolume / c.maxVolumeM3).toInt(),
                ceil(totalValue / c.maxIskValue).toInt(),
                1,
            )

        class Bucket {
            var value = 0.0
            var volume = 0.0
            val items = mutableListOf<SplitLineItem>()
        }

        val buckets = MutableList(preAlloc) { Bucket() }

        fun score(b: Bucket) =
            if (b.items.size >= MAX_ITEM_TYPES_PER_SPLIT) {
                Double.POSITIVE_INFINITY
            } else {
                b.value / c.maxIskValue + b.volume / c.maxVolumeM3
            }

        for (item in sorted) {
            var qtyLeft = item.quantity
            while (qtyLeft > 0) {
                val target =
                    buckets.filter { it.items.size < MAX_ITEM_TYPES_PER_SPLIT }.minByOrNull { score(it) }
                        ?: Bucket().also { buckets += it }

                val byValue = if (item.unitPrice > 0) ((c.maxIskValue - target.value) / item.unitPrice).toInt() else qtyLeft
                val byVolume = if (item.unitVolume > 0) ((c.maxVolumeM3 - target.volume) / item.unitVolume).toInt() else qtyLeft
                val take = minOf(qtyLeft, byValue, byVolume)

                if (take <= 0) {
                    // This split has no headroom left for this item — open a fresh one and retry.
                    buckets += Bucket()
                    continue
                }

                target.items += item.copy(quantity = take)
                target.value += take * item.unitPrice
                target.volume += take * item.unitVolume
                qtyLeft -= take
            }
        }

        val splits = buckets.filter { it.items.isNotEmpty() }.mapIndexed { i, b -> Split(i + 1, b.items) }
        return SplitPlan(SplitAlgorithm.BALANCED, splits, unplaced)
    }
}
