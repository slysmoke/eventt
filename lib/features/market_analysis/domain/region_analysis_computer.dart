import '../../market_browser/data/market_order_repository.dart';
import '../../../core/sde/sde_models.dart';
import 'import_params.dart';
import 'region_analysis_row.dart';

/// Computes region-based market analysis for a single region.
///
/// Aggregates all orders in a region and calculates:
/// - Effective source/destination prices based on PriceType
/// - Volume, order counts, sell buyout cost
/// - Margin between source and destination prices
class RegionAnalysisComputer {
  RegionAnalysisComputer._();

  /// Returns aggregated market data for all types in the region, sorted by margin descending.
  ///
  /// [orders] — all orders in the region (will be filtered by stationId if provided)
  /// [typeInfo] — map of typeId → InvType for name/volume lookup
  /// [srcPriceType] — which price to use as "source" (buy or sell)
  /// [dstPriceType] — which price to use as "destination" (buy or sell)
  /// [stationId] — if non-zero, only include orders at this station
  static List<RegionAnalysisRow> compute({
    required List<MarketOrder> orders,
    required Map<int, InvType> typeInfo,
    required PriceType srcPriceType,
    required PriceType dstPriceType,
    int stationId = 0,
  }) {
    // Group orders by type
    final ordersByType = <int, List<MarketOrder>>{};
    for (final order in orders) {
      if (stationId != 0 && order.locationId != stationId) continue;
      ordersByType.putIfAbsent(order.typeId, () => []).add(order);
    }

    final rows = <RegionAnalysisRow>[];

    for (final entry in ordersByType.entries) {
      final typeId = entry.key;
      final typeOrders = entry.value;
      final type = typeInfo[typeId];
      final typeName = type?.typeName ?? 'Unknown ($typeId)';

      // Calculate effective prices
      final srcPrice = _getEffectivePrice(typeOrders, srcPriceType);
      final dstPrice = _getEffectivePrice(typeOrders, dstPriceType);

      // Calculate volume and order counts
      int volume = 0;
      var buyOrderCount = 0;
      var sellOrderCount = 0;
      double sellBuyout = 0;

      for (final order in typeOrders) {
        volume += order.volumeRemain;
        if (order.isBuyOrder) {
          buyOrderCount++;
        } else {
          sellOrderCount++;
          sellBuyout += order.price * order.volumeRemain;
        }
      }

      final difference = dstPrice - srcPrice;
      final margin = dstPrice > 0 ? (difference / dstPrice * 100) : 0.0;

      rows.add(RegionAnalysisRow(
        typeId: typeId,
        typeName: typeName,
        srcPrice: srcPrice,
        dstPrice: dstPrice,
        difference: difference,
        volume: volume,
        buyOrderCount: buyOrderCount,
        sellOrderCount: sellOrderCount,
        margin: margin,
        sellBuyout: sellBuyout,
      ));
    }

    // Sort by margin descending
    rows.sort((a, b) => b.margin.compareTo(a.margin));
    return rows;
  }

  /// Gets effective price for a list of orders based on the price type.
  ///
  /// - [PriceType.sell]: minimum sell order price
  /// - [PriceType.buy]: maximum buy order price
  static double _getEffectivePrice(List<MarketOrder> orders, PriceType type) {
    double? result;
    for (final order in orders) {
      final isBuy = order.isBuyOrder;
      if (type == PriceType.sell && isBuy) continue;
      if (type == PriceType.buy && !isBuy) continue;

      if (result == null) {
        result = order.price;
      } else if (type == PriceType.sell) {
        if (order.price < result) result = order.price;
      } else {
        if (order.price > result) result = order.price;
      }
    }
    return result ?? 0;
  }
}
