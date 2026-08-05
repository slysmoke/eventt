package org.eventt.core.model

import kotlinx.serialization.Serializable
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

// ─── Character & Corporation ───────────────────────────────────────────────

@Serializable
data class CharacterModel(
    val id: Int,
    val name: String,
    val refreshToken: String,
    var accessToken: String = "",
    var tokenExpiry: Long = 0,
    val corporationId: Int? = null,
    var corporationName: String? = null,
)

// A character can hold some corp roles but not others (e.g. Trader without Accountant), so
// access is tracked per feature rather than as one corp-wide yes/no.
enum class CorpFeature {
    WALLET,
    ASSETS,
    ORDERS,
    CONTRACTS,
}

// ─── ESI Cache ──────────────────────────────────────────────────────────────

@Serializable
data class EsiCacheEntry(
    val endpoint: String,
    val paramsHash: String,
    val data: String,
    val expiresAt: Long,
    val source: String = "server",
    val lastFetched: Long = System.currentTimeMillis(),
    val etag: String? = null,
    val lastModified: String? = null,
)

// ─── Market ─────────────────────────────────────────────────────────────────

@Serializable
data class MarketHistoryModel(
    val typeId: Int,
    val regionId: Int,
    val date: String,
    val average: Double,
    val volume: Long,
    val orderCount: Long,
    val highest: Double,
    val lowest: Double,
)

// ─── Assets ─────────────────────────────────────────────────────────────────

@Serializable
data class AssetModel(
    val itemId: Long,
    val typeId: Int,
    val typeName: String = "",
    val quantity: Int,
    val locationId: Long,
    val locationName: String = "",
    val regionId: Int = 0,
    val regionName: String = "",
    val systemId: Int = 0,
    val systemName: String = "",
    val stationId: Long = 0,
    val stationName: String = "",
    val isSingleton: Boolean = true,
    val locationFlag: String = "",
    val estimatedPrice: Double = 0.0,
    val isCorpAsset: Boolean = false,
    val characterId: Int? = null,
    val corporationId: Int? = null,
)

// ─── Local Order Tracking (manual buy price for margin calc) ────────────────

@Serializable
data class TrackedOrderModel(
    val id: Int = 0,
    val typeId: Int,
    val typeName: String = "",
    val buyPrice: Double,
    val quantity: Int,
    val currentSellPrice: Double = 0.0,
    val stationId: Long = 0,
    val stationName: String = "",
    val notes: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val characterId: Int? = null,
    val corporationId: Int? = null,
) {
    val totalCost: Double get() = buyPrice * quantity
    val totalValue: Double get() = currentSellPrice * quantity
    val grossMargin: Double get() = totalValue - totalCost
    val marginPercent: Double get() = if (totalCost > 0) (grossMargin / totalCost) * 100.0 else 0.0
}

// ─── Static Data ────────────────────────────────────────────────────────────

@Serializable
data class StaticMarketGroupModel(
    val marketGroupId: Int,
    val name: String,
    val parentGroupId: Int? = null,
)

@Serializable
data class StaticTypeModel(
    val typeId: Int,
    val name: String,
    val groupId: Int,
    val categoryId: Int,
    val volume: Double = 0.0,
    val packagedVolume: Double = 0.0,
    val portionSize: Int = 1,
    val description: String = "",
    val iconId: Int? = null,
    val published: Boolean = false,
    val marketGroupId: Int? = null,
)

@Serializable
data class StaticGroupModel(
    val groupId: Int,
    val name: String,
    val categoryId: Int,
)

@Serializable
data class StaticCategoryModel(
    val categoryId: Int,
    val name: String,
)

@Serializable
data class StaticStationModel(
    val stationId: Long,
    val name: String,
    val systemId: Int,
    val systemName: String = "",
    val regionId: Int,
    val regionName: String = "",
    val typeId: Int = 0,
)

@Serializable
data class StaticRegionModel(
    val regionId: Int,
    val name: String,
)

@Serializable
data class StaticSystemModel(
    val systemId: Int,
    val name: String,
    val regionId: Int,
)

// ─── Contracts ──────────────────────────────────────────────────────────────

@Serializable
data class ContractModel(
    val contractId: Int,
    val issuerId: Int,
    val issuerCorpId: Int,
    val assigneeId: Int,
    val acceptorId: Int,
    val startStationId: Long,
    val endStationId: Long,
    val type: String, // item_exchange, courier, auction, unknown
    val status: String, // outstanding, in_progress, finished, cancelled, rejected, failed, pending
    val title: String = "",
    val description: String = "",
    val dateIssued: String,
    val dateExpired: String,
    val dateAccepted: String? = null,
    val dateCompleted: String? = null,
    val numDays: Int = 0,
    val price: Double = 0.0,
    val reward: Double = 0.0,
    val collateral: Double = 0.0,
    val buyout: Double = 0.0,
    val forCorp: Boolean = false,
    val isCorp: Boolean = false,
    val characterId: Int? = null,
    val corporationId: Int? = null,
)

@Serializable
data class ContractItemModel(
    val contractId: Int,
    val recordId: Int,
    val typeId: Int,
    val typeName: String = "",
    val quantity: Int,
    val rawQuantity: Int,
    val isIncluded: Boolean,
    val isSingleton: Boolean,
    val estimatedPrice: Double = 0.0,
)

// ─── Price Alerts ───────────────────────────────────────────────────────────

@Serializable
data class PriceAlertModel(
    val id: Int = 0,
    val typeId: Int,
    val typeName: String = "",
    val targetPrice: Double,
    val condition: String, // "above", "below", "percent_change"
    val stationId: Long = 0,
    val regionId: Int = 0,
    val orderType: String = "sell", // "buy", "sell"
    val enabled: Boolean = true,
    val triggered: Boolean = false,
    val triggeredAt: Long? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val characterId: Int? = null,
)

// ─── ESI Response Metadata ─────────────────────────────────────────────────

data class EsiResponseMetadata(
    val expires: Long = 0,
    val cacheControl: String = "",
    val etag: String? = null,
    val lastModified: String? = null,
    val rateLimitRemaining: Int = -1,
    val rateLimitLimit: Int = -1,
    val page: Int = 1,
    val totalPages: Int = 1,
)

// ─── Request Queue ──────────────────────────────────────────────────────────

enum class RequestSource {
    CACHE,
    SERVER,
}

enum class RequestStatus {
    QUEUED,
    IN_PROGRESS,
    COMPLETED,
    FAILED,
}

data class QueuedRequest(
    val id: String =
        java.util.UUID
            .randomUUID()
            .toString(),
    val endpoint: String,
    val description: String,
    val source: RequestSource = RequestSource.CACHE,
    val status: RequestStatus = RequestStatus.QUEUED,
    val progress: Float = 0f,
    val startTime: Long? = null,
    val endTime: Long? = null,
    val error: String? = null,
)

// ─── Wallet Summary ────────────────────────────────────────────────────────

data class WalletSummary(
    val characterId: Int? = null,
    val corporationId: Int? = null,
    val balance: Double = 0.0,
    val totalEarned: Double = 0.0,
    val totalSpent: Double = 0.0,
    val dailyBreakdown: List<DailyWalletEntry> = emptyList(),
)

@Serializable
data class DailyWalletEntry(
    val date: String,
    val income: Double = 0.0,
    val expenses: Double = 0.0,
    val net: Double = 0.0,
)

private val LOCAL_DATE_TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss")

// ESI always reports timestamps in UTC (e.g. "2026-07-11T23:45:00Z"). Any date persisted or
// compared against another date derived from ESI data must go through this exact conversion --
// two dates converted independently, or one converted and one left raw, silently drift apart
// by the local UTC offset. Strings with no UTC offset (already-local values, e.g. test
// fixtures) pass through unchanged.
fun String.utcToLocalDateTime(): String =
    try {
        Instant.parse(this).atZone(ZoneId.systemDefault()).format(LOCAL_DATE_TIME_FORMAT)
    } catch (_: DateTimeParseException) {
        this
    }

// Rolling P&L stats for a daily breakdown. Entries are sparse (only days with activity
// are present), so windows must be filtered by calendar date, not by list position/count.
data class PnlWindow(
    val todayNet: Double,
    val net7d: Double,
    val net30d: Double,
    val income30d: Double,
    val expenses30d: Double,
    val netAll: Double,
    val incomeAll: Double,
    val expensesAll: Double,
    val profitableDays: Int,
    val totalDays: Int,
)

fun List<DailyWalletEntry>.toPnlWindow(referenceDate: LocalDate = LocalDate.now()): PnlWindow {
    val today = referenceDate.toString()
    val week7Start = referenceDate.minusDays(7).toString()
    val month30Start = referenceDate.minusDays(30).toString()
    val last30d = filter { it.date >= month30Start }
    return PnlWindow(
        todayNet = firstOrNull { it.date == today }?.net ?: 0.0,
        net7d = filter { it.date >= week7Start }.sumOf { it.net },
        net30d = last30d.sumOf { it.net },
        income30d = last30d.sumOf { it.income },
        expenses30d = last30d.sumOf { it.expenses },
        netAll = sumOf { it.net },
        incomeAll = sumOf { it.income },
        expensesAll = sumOf { it.expenses },
        profitableDays = count { it.net > 0 },
        totalDays = size,
    )
}
