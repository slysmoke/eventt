package org.eventt.features.assets

import org.eventt.assets.generated.resources.*
import org.eventt.core.database.StaticDataDao
import org.eventt.core.model.AssetModel
import org.jetbrains.compose.resources.StringResource

internal const val JITA_44 = 60003760L

// Where in a station an item sits, from ESI's location_flag.
internal enum class Placement(
    val label: StringResource,
) {
    HANGAR(Res.string.pl_hangar),
    CONTAINER(Res.string.pl_container),
    FITTED(Res.string.pl_fitted),
    SHIP_HOLD(Res.string.pl_ship_hold),
    CORP_HANGAR(Res.string.pl_corp_hangar),
    DELIVERIES(Res.string.pl_deliveries),
    ASSET_SAFETY(Res.string.pl_asset_safety),
    OTHER(Res.string.pl_other),
}

private val FITTED_PREFIXES = listOf("HiSlot", "MedSlot", "LoSlot", "RigSlot", "SubSystemSlot", "ServiceSlot")

internal fun placementOf(flag: String): Placement =
    when {
        flag == "Hangar" -> Placement.HANGAR

        // Inside a container or a deployed/anchored one ("AutoFit" is ESI's flag for container contents).
        flag in setOf("Unlocked", "Locked", "AutoFit") -> Placement.CONTAINER

        FITTED_PREFIXES.any(flag::startsWith) -> Placement.FITTED

        flag == "Cargo" ||
            flag.endsWith(
                "Bay",
            ) || flag.endsWith("Hold") || flag == "FleetHangar" || flag == "ShipHangar" -> Placement.SHIP_HOLD

        flag.startsWith("CorpSAG") -> Placement.CORP_HANGAR

        flag == "Deliveries" || flag == "CorpDeliveries" -> Placement.DELIVERIES

        flag == "AssetSafety" -> Placement.ASSET_SAFETY

        else -> Placement.OTHER
    }

// A place assets sit in: a station/structure, or a solar system for items in space.
internal data class Place(
    val key: String,
    val name: String,
    val region: String,
    val regionId: Int,
    val stationId: Long?,
) {
    val isJita get() = stationId == JITA_44

    // "Jita IV - Moon 4 - Caldari Navy Assembly Plant" → "Jita IV - Moon 4": enough to tell
    // stations apart in a table column.
    val shortName get() = name.split(" - ").take(2).joinToString(" - ")
}

internal fun placeOf(
    a: AssetModel,
    unknown: String,
): Place =
    when {
        a.stationName.isNotEmpty() -> Place("st:${a.stationId}", a.stationName, a.regionName, a.regionId, a.stationId)
        a.systemName.isNotEmpty() -> Place("sys:${a.systemId}", a.systemName, a.regionName, a.regionId, null)
        else -> Place("?", unknown, "", 0, null)
    }

/** One type in one place, placement and owner — the rows the table shows (fitted modules etc. merged). */
internal data class AssetLine(
    val typeId: Int,
    val name: String,
    val group: String,
    val category: String,
    val place: Place,
    val placement: Placement,
    val owner: String?,
    val isBpc: Boolean,
    val qty: Long,
    val unitPrice: Double,
    val m3: Double,
) {
    val value get() = unitPrice * qty
    val iskPerM3 get() = if (m3 > 0) value / m3 else null
}

internal fun lines(
    assets: List<AssetModel>,
    info: Map<Int, StaticDataDao.TypeInfo>,
    ownerOf: (AssetModel) -> String?,
    unknownPlace: String,
): List<AssetLine> =
    assets
        .groupBy { a -> listOf(a.typeId, placeOf(a, unknownPlace), placementOf(a.locationFlag), ownerOf(a), a.isBlueprintCopy) }
        .map { (_, group) ->
            val a = group.first()
            val t = info[a.typeId]
            AssetLine(
                typeId = a.typeId,
                name = a.typeName.ifEmpty { "#${a.typeId}" },
                group = t?.groupName.orEmpty(),
                category = t?.categoryName.orEmpty(),
                place = placeOf(a, unknownPlace),
                placement = placementOf(a.locationFlag),
                owner = ownerOf(a),
                isBpc = a.isBlueprintCopy,
                qty = group.sumOf { it.quantity.toLong() },
                unitPrice = a.estimatedPrice,
                // An assembled ship or container takes its full volume; anything stackable packs down.
                m3 = group.sumOf { it.quantity * ((if (it.isSingleton) t?.volume else t?.packagedVolume) ?: 0.0) },
            )
        }

/** One bar in the Locations or Categories list. */
internal data class Slice(
    val key: String,
    val label: String,
    val sub: String,
    val value: Double,
    val sharePct: Double,
    val count: Int,
    val m3: Double,
)

internal fun slices(
    lines: List<AssetLine>,
    by: (AssetLine) -> Triple<String, String, String>,
): List<Slice> {
    val total = lines.sumOf { it.value }.takeIf { it > 0 } ?: 1.0
    return lines
        .groupBy(by)
        .map { (k, list) ->
            Slice(
                k.first,
                k.second,
                k.third,
                list.sumOf { it.value },
                list.sumOf { it.value } / total * 100,
                list.size,
                list.sumOf { it.m3 },
            )
        }.sortedByDescending { it.value }
}

internal fun byPlace(lines: List<AssetLine>) = slices(lines) { Triple(it.place.key, it.place.name, it.place.region) }

internal fun byCategory(
    lines: List<AssetLine>,
    other: String,
) = slices(lines) { Triple(it.category.ifEmpty { other }, it.category.ifEmpty { other }, "") }

internal data class AssetTotals(
    val value: Double,
    val lines: Int,
    val types: Int,
    val places: Int,
    val jitaValue: Double,
    val outsideValue: Double,
    val outsideM3: Double,
    val bpcs: Int,
    val unpriced: Int,
)

internal fun totals(lines: List<AssetLine>): AssetTotals {
    val outside = lines.filter { !it.place.isJita }
    return AssetTotals(
        value = lines.sumOf { it.value },
        lines = lines.size,
        types = lines.map { it.typeId }.distinct().size,
        places = lines.map { it.place.key }.distinct().size,
        jitaValue = lines.filter { it.place.isJita }.sumOf { it.value },
        outsideValue = outside.sumOf { it.value },
        outsideM3 = outside.sumOf { it.m3 },
        bpcs = lines.count { it.isBpc },
        unpriced = lines.count { it.unitPrice <= 0 && !it.isBpc },
    )
}
