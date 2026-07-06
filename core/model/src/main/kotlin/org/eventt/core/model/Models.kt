package org.eventt.core.model

import kotlinx.serialization.Serializable

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

@Serializable
data class CorporationModel(
    val id: Int,
    val name: String,
    val ticker: String = "",
    val allianceId: Int? = null,
)

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
data class MarketOrderModel(
    val orderId: Long,
    val typeId: Int,
    val stationId: Long? = null,
    val regionId: Int? = null,
    val price: Double,
    val volumeTotal: Int,
    val volumeRemaining: Int,
    val range: String = "",
    val isBuyOrder: Boolean,
    val duration: Int = 0,
    val issued: String = "",
    val minVolume: Int = 1,
    val isCorpOrder: Boolean = false,
    val characterId: Int? = null,
    val corporationId: Int? = null,
)

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

// ─── Transactions & Journal ────────────────────────────────────────────────

@Serializable
data class TransactionModel(
    val transactionId: Long,
    val date: String,
    val typeId: Int,
    val typeName: String = "",
    val quantity: Int,
    val unitPrice: Double,
    val total: Double,
    val isBuy: Boolean,
    val clientId: Int = 0,
    val clientName: String = "",
    val locationId: Long = 0,
    val locationName: String = "",
    val isCorp: Boolean = false,
    val characterId: Int? = null,
    val corporationId: Int? = null,
)

@Serializable
data class JournalEntryModel(
    val entryId: Long,
    val date: String,
    val amount: Double,
    val balance: Double,
    val reason: String = "",
    val refType: String = "",
    val firstPartyId: Int = 0,
    val firstPartyName: String = "",
    val secondPartyId: Int = 0,
    val secondPartyName: String = "",
    val taxAmount: Double? = null,
    val isCorp: Boolean = false,
    val characterId: Int? = null,
    val corporationId: Int? = null,
    val divisionId: Int? = null,
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

// ─── Watchlist ──────────────────────────────────────────────────────────────

@Serializable
data class WatchlistEntryModel(
    val id: Int = 0,
    val typeId: Int,
    val typeName: String = "",
    val watchlistName: String = "Default",
    val stationId: Long = 0,
    val regionId: Int = 0,
    val sortOrder: Int = 0,
)

@Serializable
data class WatchlistPriceSnapshot(
    val typeId: Int,
    val stationId: Long,
    val bestBuyPrice: Double = 0.0,
    val bestSellPrice: Double = 0.0,
    val spread: Double = 0.0,
    val spreadPercent: Double = 0.0,
    val volume24h: Long = 0,
    val changePercent24h: Double = 0.0,
    val changePercent7d: Double = 0.0,
    val changePercent30d: Double = 0.0,
    val sparklineData: List<Pair<String, Double>> = emptyList(),
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

// ─── Industry ───────────────────────────────────────────────────────────────

@Serializable
data class ManufacturingTemplateModel(
    val id: Int = 0,
    val name: String,
    val blueprintTypeId: Int,
    val blueprintTypeName: String = "",
    val quantity: Int = 1,
    val materialEfficiency: Int = 0,
    val timeEfficiency: Int = 100,
    val facilityId: Long = 0,
    val facilityName: String = "",
    val stationId: Long = 0,
    val stationName: String = "",
    val runCost: Double = 0.0,
    val installTax: Double = 0.0,
)

@Serializable
data class ManufacturingMaterialModel(
    val templateId: Int,
    val typeId: Int,
    val typeName: String = "",
    val requiredQuantity: Double,
    val estimatedPrice: Double = 0.0,
    val totalCost: Double = 0.0,
)

@Serializable
data class ManufacturingResultModel(
    val template: ManufacturingTemplateModel,
    val materials: List<ManufacturingMaterialModel>,
    val totalMaterialCost: Double,
    val totalCost: Double,
    val costPerUnit: Double,
    val currentMarketSellPrice: Double = 0.0,
    val profitPerUnit: Double = 0.0,
    val profitPercent: Double = 0.0,
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

// ─── Dashboard Summary ──────────────────────────────────────────────────────

data class DashboardSummary(
    val totalAssetValue: Double = 0.0,
    val totalOrderValue: Double = 0.0,
    val todayPL: Double = 0.0,
    val weekPL: Double = 0.0,
    val monthPL: Double = 0.0,
    val activeOrderCount: Int = 0,
    val activeContractCount: Int = 0,
    val triggeredAlertCount: Int = 0,
    val runningJobCount: Int = 0,
    val watchlistCount: Int = 0,
    val characterCount: Int = 0,
    val corporationCount: Int = 0,
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
