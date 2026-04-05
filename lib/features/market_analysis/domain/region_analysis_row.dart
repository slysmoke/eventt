/// A single row in the Region Analysis table.
///
/// Represents aggregated market data for one item type within a single region.
class RegionAnalysisRow {
  final int typeId;
  final String typeName;

  // Prices
  final double srcPrice;   // effective source price (buy or sell)
  final double dstPrice;   // effective destination price (buy or sell)
  final double difference; // dstPrice - srcPrice

  // Volume and orders
  final int volume;           // total remaining volume in region
  final int buyOrderCount;    // number of buy orders
  final int sellOrderCount;   // number of sell orders

  // Metrics
  final double margin;        // (difference / dstPrice) × 100, or 0 if dstPrice == 0
  final double sellBuyout;    // total ISK to buy all sell orders at this type

  const RegionAnalysisRow({
    required this.typeId,
    required this.typeName,
    required this.srcPrice,
    required this.dstPrice,
    required this.difference,
    required this.volume,
    required this.buyOrderCount,
    required this.sellOrderCount,
    required this.margin,
    required this.sellBuyout,
  });
}
