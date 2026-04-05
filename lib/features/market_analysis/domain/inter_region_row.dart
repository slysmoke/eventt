/// A single row in the Inter-Region Analysis table.
///
/// Represents a cross-region arbitrage opportunity for one item type:
/// buy in source region, sell in destination region.
class InterRegionRow {
  final int typeId;
  final String typeName;

  // Source region
  final int srcRegionId;
  final String srcRegionName;
  final double srcBuyPrice;   // max buy order price in source
  final double srcSellPrice;  // min sell order price in source
  final int srcOrderCount;
  final double srcSellBuyout; // total ISK to buy all sell orders

  // Destination region
  final int dstRegionId;
  final String dstRegionName;
  final double dstBuyPrice;   // max buy order price in destination
  final double dstSellPrice;  // min sell order price in destination
  final int dstOrderCount;
  final double dstSellBuyout; // total ISK to buy all sell orders

  // Metrics
  final double difference; // dstSellPrice - srcSellPrice (or based on price types)
  final int volume;        // total volume across both regions
  final double margin;     // (difference / dstPrice) × 100

  const InterRegionRow({
    required this.typeId,
    required this.typeName,
    required this.srcRegionId,
    required this.srcRegionName,
    required this.srcBuyPrice,
    required this.srcSellPrice,
    required this.srcOrderCount,
    required this.srcSellBuyout,
    required this.dstRegionId,
    required this.dstRegionName,
    required this.dstBuyPrice,
    required this.dstSellPrice,
    required this.dstOrderCount,
    required this.dstSellBuyout,
    required this.difference,
    required this.volume,
    required this.margin,
  });
}
