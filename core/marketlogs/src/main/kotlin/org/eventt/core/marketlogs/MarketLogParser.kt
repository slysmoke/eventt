package org.eventt.core.marketlogs

enum class MarketLogFileKind { MY_ORDERS, CORP_ORDERS, ORDER_BOOK, UNKNOWN }

data class MarketLogOrderRow(
    val orderId: Long,
    val typeId: Int,
    val charId: Int,
    val charName: String,
    val regionId: Int,
    val stationId: Long,
    val isBuyOrder: Boolean,
    val price: Double,
    val volEntered: Int,
    val volRemaining: Int,
    val minVolume: Int,
    // ISO-8601-with-T, e.g. "2026-07-09T02:57:41Z" — reformatted from the export's own
    // "2026-07-09 02:57:41.000" so it stays compatible with how OrdersScreen consumes ESI's
    // native `issued` field (take(16).replace("T", " ") for display; raw lexicographic
    // comparison against wallet-transaction dates for FIFO matching).
    val issuedIso: String,
    // Legacy XML-API order-state code — 0 is the only value that appears in these exports
    // (the source window only ever shows currently active orders).
    val orderState: Int,
    val duration: Int,
    val escrow: Double,
    val isCorp: Boolean,
)

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
 * Parses EVE's local Marketlogs CSV exports. Column sets/order differ between the three known
 * file kinds (and even between "My Orders" and "Corporation Orders", which share most but not
 * all columns in different positions) — every row is parsed by header-column-name lookup, never
 * by positional index. File kind is detected from the header's column set, not the filename
 * (many EVE item names contain hyphens themselves, e.g. "Pith A-Type X-Large Shield Booster",
 * making the `<Region>-<Item>-<timestamp>.txt` filename ambiguous to split).
 */
object MarketLogParser {
    private val MY_ORDERS_HEADER =
        setOf(
            "orderID",
            "typeID",
            "charID",
            "charName",
            "regionID",
            "regionName",
            "solarSystemID",
            "solarSystemName",
            "stationID",
            "stationName",
            "range",
            "bid",
            "price",
            "volEntered",
            "volRemaining",
            "minVolume",
            "issueDate",
            "orderState",
            "duration",
            "escrow",
            "isCorp",
            "accountID",
            "accountOwnerID",
            "accountKey",
        )
    private val CORP_ORDERS_HEADER =
        setOf(
            "orderID",
            "typeID",
            "charID",
            "charName",
            "regionID",
            "regionName",
            "stationID",
            "stationName",
            "range",
            "bid",
            "price",
            "volEntered",
            "volRemaining",
            "issueDate",
            "orderState",
            "minVolume",
            "accountID",
            "duration",
            "isCorp",
            "solarSystemID",
            "solarSystemName",
            "escrow",
            "keyID",
        )
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
            MY_ORDERS_HEADER -> MarketLogFileKind.MY_ORDERS
            CORP_ORDERS_HEADER -> MarketLogFileKind.CORP_ORDERS
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

    // Numeric fields are inconsistently written with/without a decimal point across the two
    // order-export formats (e.g. volEntered "2" vs volRemaining "2.0") — always route through
    // Double first so both forms parse.
    private fun String.toIntLoose(): Int = toDoubleOrNull()?.toInt() ?: 0

    /** Shared by "My Orders" and "Corporation Orders" — same field semantics, different column layouts. */
    fun parseOrderLog(lines: List<String>): List<MarketLogOrderRow> {
        if (lines.size < 2) return emptyList()
        val idx = indexMap(lines[0])
        val isCorpFile = "keyID" in idx
        return lines.drop(1).mapNotNull { line ->
            if (line.isBlank()) return@mapNotNull null
            val cols = line.split(",")
            runCatching {
                MarketLogOrderRow(
                    orderId = cols.field(idx, "orderID").toLong(),
                    typeId = cols.field(idx, "typeID").toInt(),
                    charId = cols.field(idx, "charID").toInt(),
                    charName = cols.field(idx, "charName"),
                    regionId = cols.field(idx, "regionID").toInt(),
                    stationId = cols.field(idx, "stationID").toLong(),
                    isBuyOrder = cols.field(idx, "bid").equals("True", ignoreCase = true),
                    price = cols.field(idx, "price").toDouble(),
                    volEntered = cols.field(idx, "volEntered").toIntLoose(),
                    volRemaining = cols.field(idx, "volRemaining").toIntLoose(),
                    minVolume = cols.field(idx, "minVolume").toIntLoose(),
                    issuedIso = reformatIssueDate(cols.field(idx, "issueDate")),
                    orderState = cols.field(idx, "orderState").toIntLoose(),
                    duration = cols.field(idx, "duration").toIntLoose(),
                    escrow = cols.field(idx, "escrow").toDoubleOrNull() ?: 0.0,
                    isCorp = isCorpFile || cols.field(idx, "isCorp").equals("True", ignoreCase = true),
                )
            }.getOrNull()
        }
    }

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
