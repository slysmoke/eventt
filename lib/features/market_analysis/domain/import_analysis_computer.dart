import '../../market_browser/data/market_order_repository.dart';
import '../../../core/sde/sde_models.dart';
import 'import_params.dart';
import 'import_row.dart';

/// Computes import margin analysis from raw regional order sets.
class ImportAnalysisComputer {
  ImportAnalysisComputer._();

  /// Returns profitable import opportunities sorted by margin descending.
  ///
  /// Filters orders to the given station IDs.
  /// Price type controls which order book side is used:
  ///   - [PriceType.sell] → min sell order price (you buy/sell at market)
  ///   - [PriceType.buy]  → max buy order price (you place/fill a buy order)
  ///
  /// [typeFilter] — if non-null, only include types in this set.
  static List<ImportRow> compute({
    required List<MarketOrder> allSrcOrders,
    required List<MarketOrder> allDstOrders,
    required int srcStationId,
    required int dstStationId,
    required Map<int, InvType> typeInfo,
    required ImportParams params,
    Set<int>? typeFilter,
  }) {
    // ── Effective price per type per station ──────────────────────────────
    // Source: min sell OR max buy depending on srcPriceType
    final srcEffective = _buildPrices(
      allSrcOrders, srcStationId, params.srcPriceType,
    );

    // Destination: min sell OR max buy depending on dstPriceType
    final dstEffective = _buildPrices(
      allDstOrders, dstStationId, params.dstPriceType,
    );

    // If hideEmptySrcSell: build set of types that DO have sell orders at src
    final srcSellExists = params.hideEmptySrcSell
        ? _buildPrices(allSrcOrders, srcStationId, PriceType.sell).keys.toSet()
        : null;

    final rows = <ImportRow>[];

    for (final MapEntry(:key, :value) in srcEffective.entries) {
      if (typeFilter != null && !typeFilter.contains(key)) continue;
      if (srcSellExists != null && !srcSellExists.contains(key)) continue;

      final dst = dstEffective[key];
      if (dst == null) continue;

      final type = typeInfo[key];
      final name = type?.typeName ?? 'Unknown ($key)';
      final itemVolumeM3 = type?.volume ?? 0.0;

      // Count orders and volume at each station
      final srcOrderCount = _countOrdersAtStation(allSrcOrders, srcStationId, key, params.srcPriceType);
      final dstOrderCount = _countOrdersAtStation(allDstOrders, dstStationId, key, params.dstPriceType);
      final dstRemainingVolume = _sumVolumeAtStation(allDstOrders, dstStationId, key, params.dstPriceType);

      // Collateral base price
      final collateralBase = params.collateralPriceType == PriceType.buy
          ? value  // collateral on source price
          : dst;   // collateral on destination price

      // Import price = (srcPrice + logistics) × (1 + priceMod%)
      final logistics =
          itemVolumeM3 * params.pricePerM3 + collateralBase * params.collateralPct / 100;
      final importPrice = (value + logistics) * (1 + params.srcPriceMod / 100);

      if (dst <= importPrice) continue;

      final diff = dst - importPrice;
      final margin = diff / dst * 100;

      if (margin < params.minMarginPct) continue;
      final maxM = params.maxMarginPct;
      if (maxM != null && margin > maxM) continue;

      // Projected volume: for now, use destination remaining volume
      // (Later: integrate with market history for avg daily volume)
      final projectedVolume = dstRemainingVolume;
      final projectedProfit = projectedVolume * diff;

      rows.add(ImportRow(
        typeId: key,
        typeName: name,
        sourcePrice: value,
        destPrice: dst,
        importPrice: importPrice,
        priceDiff: diff,
        margin: margin,
        sourceOrderCount: srcOrderCount,
        destOrderCount: dstOrderCount,
        destRemainingVolume: dstRemainingVolume,
        projectedVolume: projectedVolume,
        projectedProfit: projectedProfit,
      ));
    }

    rows.sort((a, b) => b.margin.compareTo(a.margin));
    return rows;
  }

  /// Builds a {typeId → effective price} map for orders at [stationId].
  ///
  /// - [PriceType.sell]: minimum sell order price (excludes buy orders)
  /// - [PriceType.buy]:  maximum buy order price (excludes sell orders)
  static Map<int, double> _buildPrices(
    List<MarketOrder> orders,
    int stationId,
    PriceType type,
  ) {
    final result = <int, double>{};
    for (final o in orders) {
      if (o.locationId != stationId) continue;
      final isBuy = o.isBuyOrder;
      if (type == PriceType.sell && isBuy) continue;
      if (type == PriceType.buy && !isBuy) continue;

      final existing = result[o.typeId];
      if (existing == null) {
        result[o.typeId] = o.price;
      } else if (type == PriceType.sell) {
        if (o.price < existing) result[o.typeId] = o.price; // min sell
      } else {
        if (o.price > existing) result[o.typeId] = o.price; // max buy
      }
    }
    return result;
  }

  /// Counts orders for a specific type at a station.
  static int _countOrdersAtStation(
    List<MarketOrder> orders,
    int stationId,
    int typeId,
    PriceType type,
  ) {
    var count = 0;
    for (final o in orders) {
      if (o.locationId != stationId) continue;
      if (o.typeId != typeId) continue;
      if (type == PriceType.sell && o.isBuyOrder) continue;
      if (type == PriceType.buy && !o.isBuyOrder) continue;
      count++;
    }
    return count;
  }

  /// Sums remaining volume for a specific type at a station.
  static int _sumVolumeAtStation(
    List<MarketOrder> orders,
    int stationId,
    int typeId,
    PriceType type,
  ) {
    var total = 0;
    for (final o in orders) {
      if (o.locationId != stationId) continue;
      if (o.typeId != typeId) continue;
      if (type == PriceType.sell && o.isBuyOrder) continue;
      if (type == PriceType.buy && !o.isBuyOrder) continue;
      total += o.volumeRemain;
    }
    return total;
  }
}
