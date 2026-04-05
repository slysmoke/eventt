import '../../market_browser/data/market_order_repository.dart';
import '../../../core/sde/sde_models.dart';
import 'import_params.dart';
import 'inter_region_row.dart';

/// Computes cross-region arbitrage opportunities between two regions.
///
/// For each item type present in both regions, calculates:
/// - Source region prices (buy/sell)
/// - Destination region prices (buy/sell)
/// - Price difference and margin
/// - Volume and order counts
class InterRegionAnalysisComputer {
  InterRegionAnalysisComputer._();

  /// Returns profitable inter-region opportunities sorted by margin descending.
  ///
  /// [srcOrders] — all orders in the source region
  /// [dstOrders] — all orders in the destination region
  /// [srcRegion] — source region info
  /// [dstRegion] — destination region info
  /// [typeInfo] — map of typeId → InvType for name lookup
  /// [srcPriceType] — which price to use as "source" (buy or sell)
  /// [dstPriceType] — which price to use as "destination" (buy or sell)
  static List<InterRegionRow> compute({
    required List<MarketOrder> srcOrders,
    required List<MarketOrder> dstOrders,
    required MapRegion srcRegion,
    required MapRegion dstRegion,
    required Map<int, InvType> typeInfo,
    required PriceType srcPriceType,
    required PriceType dstPriceType,
  }) {
    // Group orders by type for each region
    final srcByType = _groupByType(srcOrders);
    final dstByType = _groupByType(dstOrders);

    // Find types present in both regions
    final commonTypes = srcByType.keys.toSet().intersection(dstByType.keys.toSet());

    final rows = <InterRegionRow>[];

    for (final typeId in commonTypes) {
      final type = typeInfo[typeId];
      final typeName = type?.typeName ?? 'Unknown ($typeId)';

      final srcTypeOrders = srcByType[typeId]!;
      final dstTypeOrders = dstByType[typeId]!;

      // Calculate prices
      final srcBuyPrice = _getMaxBuyPrice(srcTypeOrders);
      final srcSellPrice = _getMinSellPrice(srcTypeOrders);
      final dstBuyPrice = _getMaxBuyPrice(dstTypeOrders);
      final dstSellPrice = _getMinSellPrice(dstTypeOrders);

      // Calculate order counts
      final srcOrderCount = srcTypeOrders.length;
      final dstOrderCount = dstTypeOrders.length;

      // Calculate sell buyouts
      final srcSellBuyout = _calcSellBuyout(srcTypeOrders);
      final dstSellBuyout = _calcSellBuyout(dstTypeOrders);

      // Calculate volume
      final volume = srcTypeOrders.fold<int>(0, (sum, o) => sum + o.volumeRemain) +
                     dstTypeOrders.fold<int>(0, (sum, o) => sum + o.volumeRemain);

      // Calculate difference and margin based on price types
      final srcPrice = srcPriceType == PriceType.buy ? srcBuyPrice : srcSellPrice;
      final dstPrice = dstPriceType == PriceType.buy ? dstBuyPrice : dstSellPrice;

      final difference = dstPrice - srcPrice;
      final margin = dstPrice > 0 ? (difference / dstPrice * 100) : 0.0;

      rows.add(InterRegionRow(
        typeId: typeId,
        typeName: typeName,
        srcRegionId: srcRegion.regionId,
        srcRegionName: srcRegion.regionName,
        srcBuyPrice: srcBuyPrice,
        srcSellPrice: srcSellPrice,
        srcOrderCount: srcOrderCount,
        srcSellBuyout: srcSellBuyout,
        dstRegionId: dstRegion.regionId,
        dstRegionName: dstRegion.regionName,
        dstBuyPrice: dstBuyPrice,
        dstSellPrice: dstSellPrice,
        dstOrderCount: dstOrderCount,
        dstSellBuyout: dstSellBuyout,
        difference: difference,
        volume: volume,
        margin: margin,
      ));
    }

    // Sort by margin descending
    rows.sort((a, b) => b.margin.compareTo(a.margin));
    return rows;
  }

  /// Groups orders by typeId.
  static Map<int, List<MarketOrder>> _groupByType(List<MarketOrder> orders) {
    final result = <int, List<MarketOrder>>{};
    for (final order in orders) {
      result.putIfAbsent(order.typeId, () => []).add(order);
    }
    return result;
  }

  /// Gets maximum buy order price for a list of orders.
  static double _getMaxBuyPrice(List<MarketOrder> orders) {
    double? max;
    for (final order in orders) {
      if (!order.isBuyOrder) continue;
      if (max == null || order.price > max) max = order.price;
    }
    return max ?? 0;
  }

  /// Gets minimum sell order price for a list of orders.
  static double _getMinSellPrice(List<MarketOrder> orders) {
    double? min;
    for (final order in orders) {
      if (order.isBuyOrder) continue;
      if (min == null || order.price < min) min = order.price;
    }
    return min ?? 0;
  }

  /// Calculates total ISK to buy all sell orders (price × volume for each).
  static double _calcSellBuyout(List<MarketOrder> orders) {
    double total = 0;
    for (final order in orders) {
      if (order.isBuyOrder) continue;
      total += order.price * order.volumeRemain;
    }
    return total;
  }
}
