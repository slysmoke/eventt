package org.eventt.features.tools

import org.eventt.core.database.StaticDataDao
import org.eventt.core.model.StaticTypeModel

data class ParsedItemLine(
    val rawName: String,
    val quantity: Int,
)

data class ParseWarning(
    val lineText: String,
    val reason: String,
)

data class ResolvedItem(
    val typeId: Int,
    val name: String,
    val quantity: Int,
    val unitVolume: Double,
    val categoryId: Int,
)

/**
 * Parses and resolves EVE's inventory-copy clipboard format ("name\tquantity" per line, exactly
 * what the in-game inventory window's Ctrl+C produces) against local static data. Unlike the
 * original web tool this is ported from — which silently dropped malformed lines and aborted the
 * whole calculation on the first unresolved item name — every problem is surfaced as a
 * ParseWarning and processing continues with whatever did resolve.
 */
object ToolsInputParser {
    fun parse(text: String): Pair<List<ParsedItemLine>, List<ParseWarning>> {
        val items = mutableListOf<ParsedItemLine>()
        val warnings = mutableListOf<ParseWarning>()
        text.lines().forEach { line ->
            if (line.isBlank()) return@forEach
            val cols = line.split("\t")
            if (cols.size < 2) {
                warnings += ParseWarning(line, "expected 2 tab-separated columns (name, quantity)")
                return@forEach
            }
            val qty = cols[1].trim().replace(",", "").toIntOrNull()
            if (qty == null) {
                warnings += ParseWarning(line, "quantity '${cols[1].trim()}' is not a whole number")
                return@forEach
            }
            val name = cols[0].trim()
            if (name.isEmpty()) {
                warnings += ParseWarning(line, "item name is empty")
                return@forEach
            }
            items += ParsedItemLine(name, qty)
        }
        return items to warnings
    }

    fun resolve(
        parsed: List<ParsedItemLine>,
        volumeSelector: (StaticTypeModel) -> Double,
    ): Pair<List<ResolvedItem>, List<ParseWarning>> {
        val resolved = mutableListOf<ResolvedItem>()
        val warnings = mutableListOf<ParseWarning>()
        parsed.forEach { line ->
            val type = StaticDataDao.getTypeByExactName(line.rawName)
            if (type == null) {
                warnings += ParseWarning("${line.rawName}\t${line.quantity}", "item name not found in static data")
                return@forEach
            }
            resolved += ResolvedItem(type.typeId, type.name, line.quantity, volumeSelector(type), type.categoryId)
        }
        return resolved to warnings
    }
}
