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

    // EVE market row, tab-separated:
    //  Sell (5 fields): Range | Volume | Price ISK | Station | Expires
    //  Buy  (7 fields): Range | Volume | Price ISK | Station | Range | MinVol | Expires
    fun parse(text: String?): ParsedOrder? {
        val line =
            text
                ?.trim()
                ?.lines()
                ?.firstOrNull()
                ?.trim() ?: return null
        val parts = line.split("\t").map { it.trim() }.filter { it.isNotEmpty() }
        if (parts.size < 4) return null

        val priceIdx = parts.indexOfFirst { it.contains("ISK") }
        if (priceIdx < 1) return null

        val price =
            parts[priceIdx]
                .replace(",", "")
                .replace(" ISK", "")
                .trim()
                .toDoubleOrNull() ?: return null
        val volume = parts[priceIdx - 1].replace(",", "").toLongOrNull() ?: 0L
        val location = parts.getOrNull(priceIdx + 1) ?: ""
        val isBuy = parts.size >= 7

        return ParsedOrder(price = price, volume = volume, location = location, isBuy = isBuy)
    }
}
