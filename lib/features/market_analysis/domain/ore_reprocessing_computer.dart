import '../../market_browser/data/market_order_repository.dart';
import '../../../core/sde/sde_models.dart';
import 'import_params.dart';
import 'ore_reprocessing_row.dart';

/// Parameters for ore reprocessing arbitrage.
class OreReprocessingParams {
  /// Station efficiency (0.0 - 1.0). Base yield percentage.
  final double stationEfficiency;

  /// Reprocessing skill level (0-5). Each level adds 2% to yield.
  final int reprocessingSkillLevel;

  /// Reprocessing Efficiency skill level (0-5). Each level adds 2% to yield.
  final int reprocessingEfficiencyLevel;

  /// Implant bonus percentage (0-5 for standard implants).
  final double implantBonus;

  /// Destination price type: sell to buy orders or place sell orders.
  final PriceType dstPriceType;

  /// Station tax percentage (0-100).
  final double stationTax;

  /// Whether to include station tax in calculations.
  final bool useStationTax;

  /// Ignore orders with minimum volume > 1.
  final bool ignoreMinVolume;

  /// Only high-sec orders (security status >= 0.5).
  final bool onlyHighSec;

  /// Sell volume limit as percentage (0.0-1.0).
  /// Limits how much of the market volume we try to sell.
  final double sellVolumeLimit;

  const OreReprocessingParams({
    this.stationEfficiency = 0.5,
    this.reprocessingSkillLevel = 0,
    this.reprocessingEfficiencyLevel = 0,
    this.implantBonus = 0,
    this.dstPriceType = PriceType.buy,
    this.stationTax = 0,
    this.useStationTax = false,
    this.ignoreMinVolume = false,
    this.onlyHighSec = false,
    this.sellVolumeLimit = 1.0,
  });

  /// Calculate effective reprocessing yield.
  /// Formula: baseYield × (1 + reprocessing × 0.03) × (1 + efficiency × 0.02) × (1 + implant/100)
  double get effectiveYield {
    return stationEfficiency *
        (1 + reprocessingSkillLevel * 0.03) *
        (1 + reprocessingEfficiencyLevel * 0.02) *
        (1 + implantBonus / 100);
  }
}

/// Computes ore reprocessing arbitrage opportunities.
///
/// For each ore type:
/// 1. Buy ore at source station/region
/// 2. Reprocess into minerals
/// 3. Sell minerals at destination station/region
/// 4. Calculate profit after costs
class OreReprocessingComputer {
  OreReprocessingComputer._();

  /// Returns profitable reprocessing opportunities sorted by margin descending.
  ///
  /// [srcOrders] — sell orders for ore at source
  /// [dstOrders] — buy/sell orders for minerals at destination
  /// [oreInfo] — map of oreTypeId → OreReprocessingInfo
  /// [typeInfo] — map of typeId → InvType for name lookup
  /// [params] — reprocessing parameters
  static List<OreReprocessingRow> compute({
    required List<MarketOrder> srcOrders,
    required List<MarketOrder> dstOrders,
    required Map<int, OreReprocessingInfo> oreInfo,
    required Map<int, InvType> typeInfo,
    required OreReprocessingParams params,
  }) {
    // Build mineral price maps at destination
    final mineralBuyPrices = _buildMineralBuyPrices(dstOrders, params);
    final mineralSellPrices = _buildMineralSellPrices(dstOrders, params);

    final rows = <OreReprocessingRow>[];

    for (final entry in oreInfo.entries) {
      final oreTypeId = entry.key;
      final info = entry.value;

      // Get ore sell orders at source
      final oreSellOrders = srcOrders
          .where((o) => o.typeId == oreTypeId && !o.isBuyOrder)
          .toList()
        ..sort((a, b) => a.price.compareTo(b.price)); // cheapest first

      if (oreSellOrders.isEmpty) continue;

      // Simulate reprocessing
      double totalCost = 0;
      double totalProfit = 0;
      int totalVolume = 0;

      int oreIndex = 0;
      int remainingInOrder = oreSellOrders.isNotEmpty ? oreSellOrders.first.volumeRemain : 0;

      while (oreIndex < oreSellOrders.length) {
        final currentOrder = oreSellOrders[oreIndex];
        final availableVolume = remainingInOrder;

        if (availableVolume <= 0) {
          oreIndex++;
          if (oreIndex < oreSellOrders.length) {
            remainingInOrder = oreSellOrders[oreIndex].volumeRemain;
          }
          continue;
        }

        final portionsToProcess = (availableVolume / info.portionSize).floor();
        if (portionsToProcess <= 0) break;

        // Calculate cost for this batch
        final volumeToBuy = portionsToProcess * info.portionSize;
        final cost = volumeToBuy * currentOrder.price;

        // Calculate mineral output
        double batchProfit = 0;
        for (final material in info.materials) {
          final materialQuantity = portionsToProcess * material.quantity;
          final adjustedQuantity = (materialQuantity * params.effectiveYield).round();

          final mineralPrice = params.dstPriceType == PriceType.buy
              ? mineralBuyPrices[material.typeId]
              : mineralSellPrices[material.typeId];

          if (mineralPrice == null || mineralPrice <= 0) continue;

          batchProfit += adjustedQuantity * mineralPrice;
        }

        // Apply station tax if enabled
        if (params.useStationTax) {
          totalCost += batchProfit * params.stationTax / 100;
        }

        totalCost += cost;
        totalProfit += batchProfit;
        totalVolume += volumeToBuy;

        // Update remaining volume
        remainingInOrder -= volumeToBuy;

        // Check if still profitable
        if (totalProfit <= totalCost) {
          // Stop if no longer profitable
          break;
        }
      }

      // Only include if profitable
      if (totalProfit > totalCost && totalCost > 0) {
        final difference = totalProfit - totalCost;
        final margin = (difference / totalCost) * 100;

        final oreType = typeInfo[oreTypeId];
        final oreName = oreType?.typeName ?? 'Unknown ($oreTypeId)';

        rows.add(OreReprocessingRow(
          oreTypeId: oreTypeId,
          oreName: oreName,
          volume: totalVolume,
          totalProfit: totalProfit,
          totalCost: totalCost,
          difference: difference,
          margin: margin,
        ));
      }
    }

    // Sort by margin descending
    rows.sort((a, b) => b.margin.compareTo(a.margin));
    return rows;
  }

  /// Builds map of mineral typeId → max buy price at destination.
  static Map<int, double> _buildMineralBuyPrices(
    List<MarketOrder> dstOrders,
    OreReprocessingParams params,
  ) {
    final result = <int, double>{};
    for (final order in dstOrders) {
      if (!order.isBuyOrder) continue;
      final existing = result[order.typeId];
      if (existing == null || order.price > existing) {
        result[order.typeId] = order.price;
      }
    }
    return result;
  }

  /// Builds map of mineral typeId → min sell price at destination.
  static Map<int, double> _buildMineralSellPrices(
    List<MarketOrder> dstOrders,
    OreReprocessingParams params,
  ) {
    final result = <int, double>{};
    for (final order in dstOrders) {
      if (order.isBuyOrder) continue;
      final existing = result[order.typeId];
      if (existing == null || order.price < existing) {
        result[order.typeId] = order.price;
      }
    }
    return result;
  }
}
