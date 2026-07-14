package org.eventt.core.marketlogs

enum class MarketLogFileKind { ORDER_BOOK, UNKNOWN }

data class MarketLogBookRow(
    val orderId: Long,
    val typeId: Int,
    val regionId: Int,
    val stationId: Long,
    val solarSystemId: Int,
    val isBuyOrder: Boolean,
    val price: Double,
    val volEntered: Int,
    val volRemaining: Int,
    val minVolume: Int,
    val issuedIso: String,
    val duration: Int,
    val jumps: Int,
)

/**
 * Parses EVE's local Marketlogs CSV exports. Only the "Order Book" export is recognized — every
 * row is parsed by header-column-name lookup, never by positional index. File kind is detected
 * from the header's column set, not the filename (many EVE item names contain hyphens themselves,
 * e.g. "Pith A-Type X-Large Shield Booster", making the `<Region>-<Item>-<timestamp>.txt`
 * filename ambiguous to split).
 */
object MarketLogParser {
    private val ORDER_BOOK_HEADER =
        setOf(
            "price",
            "volRemaining",
            "typeID",
            "range",
            "orderID",
            "volEntered",
            "minVolume",
            "bid",
            "issueDate",
            "duration",
            "stationID",
            "regionID",
            "solarSystemID",
            "jumps",
        )

    // Every header has a trailing comma (empty final column) — filtered out here.
    private fun headerColumns(headerLine: String): Set<String> =
        headerLine
            .split(",")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toSet()

    fun detectKind(headerLine: String): MarketLogFileKind =
        when (headerColumns(headerLine)) {
            ORDER_BOOK_HEADER -> MarketLogFileKind.ORDER_BOOK
            else -> MarketLogFileKind.UNKNOWN
        }

    // "2026-07-09 02:57:41.000" -> "2026-07-09T02:57:41Z". The export never carries a timezone;
    // EVE server time is UTC.
    internal fun reformatIssueDate(raw: String): String {
        val datePart = raw.substringBefore(" ")
        val timePart = raw.substringAfter(" ").substringBefore(".")
        return "${datePart}T${timePart}Z"
    }

    private fun indexMap(headerLine: String): Map<String, Int> =
        headerLine
            .split(",")
            .mapIndexed { i, name -> name.trim() to i }
            .filter { it.first.isNotEmpty() }
            .toMap()

    private fun List<String>.field(
        idx: Map<String, Int>,
        name: String,
    ): String = idx[name]?.let { getOrNull(it)?.trim() } ?: ""

    // Numeric fields are inconsistently written with/without a decimal point (e.g. volEntered
    // "2" vs volRemaining "2.0") — always route through Double first so both forms parse.
    private fun String.toIntLoose(): Int = toDoubleOrNull()?.toInt() ?: 0

    fun parseOrderBook(lines: List<String>): List<MarketLogBookRow> {
        if (lines.size < 2) return emptyList()
        val idx = indexMap(lines[0])
        return lines.drop(1).mapNotNull { line ->
            if (line.isBlank()) return@mapNotNull null
            val cols = line.split(",")
            runCatching {
                MarketLogBookRow(
                    orderId = cols.field(idx, "orderID").toLong(),
                    typeId = cols.field(idx, "typeID").toInt(),
                    regionId = cols.field(idx, "regionID").toInt(),
                    stationId = cols.field(idx, "stationID").toLong(),
                    solarSystemId = cols.field(idx, "solarSystemID").toInt(),
                    isBuyOrder = cols.field(idx, "bid").equals("True", ignoreCase = true),
                    price = cols.field(idx, "price").toDouble(),
                    volEntered = cols.field(idx, "volEntered").toIntLoose(),
                    volRemaining = cols.field(idx, "volRemaining").toIntLoose(),
                    minVolume = cols.field(idx, "minVolume").toIntLoose(),
                    issuedIso = reformatIssueDate(cols.field(idx, "issueDate")),
                    duration = cols.field(idx, "duration").toIntLoose(),
                    jumps = cols.field(idx, "jumps").toIntLoose(),
                )
            }.getOrNull()
        }
    }
}
