import '../../../core/sde/sde_models.dart';
import '../../market_browser/data/market_order_repository.dart';
import 'import_params.dart';
import 'scrapmetal_reprocessing_row.dart';

/// Parameters for scrapmetal reprocessing arbitrage.
class ScrapmetalReprocessingParams {
  /// Station base efficiency (0.0–1.0).
  final double stationEfficiency;

  /// Scrapmetal Processing skill level (0–5). Each level adds +2% to yield.
  final int scrapmetalSkillLevel;

  /// Destination price type: sell minerals to buy orders (buy) or place sell
  /// orders (sell).
  final PriceType dstPriceType;

  const ScrapmetalReprocessingParams({
    this.stationEfficiency = 0.5,
    this.scrapmetalSkillLevel = 0,
    this.dstPriceType = PriceType.buy,
  });

  /// Effective reprocessing yield.
  /// Formula: stationEfficiency × (1 + scrapmetalSkillLevel × 0.02)
  double get effectiveYield =>
      stationEfficiency * (1 + scrapmetalSkillLevel * 0.02);
}

/// Computes scrapmetal reprocessing arbitrage opportunities.
///
/// For each item type in [scrapmetalInfo]:
/// 1. Buy the item from sell orders at source
/// 2. Reprocess into minerals
/// 3. Sell minerals at destination
/// 4. Include only if profitable
class ScrapmetalReprocessingComputer {
  ScrapmetalReprocessingComputer._();

  /// Returns profitable scrapmetal opportunities sorted by margin descending.
  static List<ScrapmetalReprocessingRow> compute({
    required List<MarketOrder> srcOrders,
    required List<MarketOrder> dstOrders,
    required Map<int, ScrapmetalInfo> scrapmetalInfo,
    required Map<int, InvType> typeInfo,
    required ScrapmetalReprocessingParams params,
  }) {
    final mineralBuyPrices = _buildBuyPrices(dstOrders);
    final mineralSellPrices = _buildSellPrices(dstOrders);

    final rows = <ScrapmetalReprocessingRow>[];

    for (final entry in scrapmetalInfo.entries) {
      final typeId = entry.key;
      final info = entry.value;

      final sellOrders = srcOrders
          .where((o) => o.typeId == typeId && !o.isBuyOrder)
          .toList()
        ..sort((a, b) => a.price.compareTo(b.price)); // cheapest first

      if (sellOrders.isEmpty) continue;

      double totalCost = 0;
      double totalRevenue = 0;
      int totalVolume = 0;

      for (final order in sellOrders) {
        final portions = (order.volumeRemain / info.portionSize).floor();
        if (portions <= 0) continue;

        final unitsToBuy = portions * info.portionSize;
        final cost = unitsToBuy * order.price;

        double revenue = 0;
        for (final mat in info.materials) {
          final outQty = (portions * mat.quantity * params.effectiveYield).floor();
          final price = params.dstPriceType == PriceType.buy
              ? mineralBuyPrices[mat.typeId]
              : mineralSellPrices[mat.typeId];
          if (price == null || price <= 0) continue;
          revenue += outQty * price;
        }

        totalCost += cost;
        totalRevenue += revenue;
        totalVolume += unitsToBuy;
      }

      if (totalRevenue <= totalCost || totalCost <= 0) continue;

      final difference = totalRevenue - totalCost;
      final margin = difference / totalCost * 100;
      final typeName =
          typeInfo[typeId]?.typeName ?? 'Unknown ($typeId)';

      rows.add(ScrapmetalReprocessingRow(
        typeId: typeId,
        typeName: typeName,
        volume: totalVolume,
        totalProfit: totalRevenue,
        totalCost: totalCost,
        difference: difference,
        margin: margin,
      ));
    }

    rows.sort((a, b) => b.margin.compareTo(a.margin));
    return rows;
  }

  static Map<int, double> _buildBuyPrices(List<MarketOrder> orders) {
    final result = <int, double>{};
    for (final o in orders) {
      if (!o.isBuyOrder) continue;
      final ex = result[o.typeId];
      if (ex == null || o.price > ex) result[o.typeId] = o.price;
    }
    return result;
  }

  static Map<int, double> _buildSellPrices(List<MarketOrder> orders) {
    final result = <int, double>{};
    for (final o in orders) {
      if (o.isBuyOrder) continue;
      final ex = result[o.typeId];
      if (ex == null || o.price < ex) result[o.typeId] = o.price;
    }
    return result;
  }
}
