package org.eventt.features.overlay

import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor

object ClipboardParser {
    data class ParsedOrder(
        val price: Double,
        val volume: Long,
        val location: String,
        val isBuy: Boolean,
    )

    fun readClipboard(): String? =
        try {
            Toolkit.getDefaultToolkit().systemClipboard.getData(DataFlavor.stringFlavor) as? String
        } catch (_: Exception) {
            null
        }

    // EVE market row, tab-separated. Regular items carry a Range/Station pair the aggregated
    // PLEX market window doesn't show, so field count alone can't tell rows apart — this instead
    // locates Price by its "ISK" suffix and works outward from there:
    //  Sell, regular (5 fields): Range | Volume | Price ISK | Station | Expires
    //  Sell, PLEX    (3 fields):         Volume | Price ISK |         | Expires
    //  Buy,  regular (7 fields): Range | Volume | Price ISK | Station | Range | MinVol | Expires
    //  Buy,  PLEX    (4 fields):         Volume | Price ISK |         | MinVol | Expires
    fun parse(text: String?): ParsedOrder? {
        val line =
            text
                ?.trim()
                ?.lines()
                ?.firstOrNull()
                ?.trim() ?: return null
        val parts = line.split("\t").map { it.trim() }.filter { it.isNotEmpty() }
        if (parts.size < 3) return null

        val priceIdx = parts.indexOfFirst { it.contains("ISK") }
        if (priceIdx < 1) return null

        val price =
            parts[priceIdx]
                .replace(",", "")
                .replace(" ISK", "")
                .trim()
                .toDoubleOrNull() ?: return null
        val volume = parts[priceIdx - 1].replace(",", "").toLongOrNull() ?: 0L

        // The field right before the last one is EVE's "Min volume" column, a plain integer —
        // present only on buy rows, whatever else the row's layout looks like (regular or PLEX).
        val minVolIdx = parts.size - 2
        val isBuy = minVolIdx > priceIdx && parts[minVolIdx].replace(",", "").toLongOrNull() != null

        // Station only exists as its own field on regular-item rows, immediately after Price —
        // distinguishable from PLEX's shorter rows by there being more fields left after Price
        // than just the trailing Min volume/Expires columns.
        val fieldsAfterPrice = parts.size - 1 - priceIdx
        val location = if (fieldsAfterPrice >= (if (isBuy) 3 else 2)) parts.getOrNull(priceIdx + 1) ?: "" else ""

        return ParsedOrder(price = price, volume = volume, location = location, isBuy = isBuy)
    }
}
