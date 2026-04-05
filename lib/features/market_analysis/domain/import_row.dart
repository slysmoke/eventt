/// One row in the import analysis table.
class ImportRow {
  final int typeId;
  final String typeName;

  // Price data
  final double sourcePrice;  // effective price at source (percentile or best)
  final double destPrice;    // effective price at destination (percentile or best)
  final double importPrice;  // sourcePrice + logistics cost
  final double priceDiff;    // destPrice − importPrice (profit per unit)
  final double margin;       // priceDiff / destPrice × 100

  // Volume statistics (from order book analysis)
  final int sourceOrderCount;    // number of orders at source
  final int destOrderCount;      // number of orders at destination
  final int destRemainingVolume; // total remaining volume at destination

  // Projected metrics
  final int projectedVolume; // estimated volume that can be sold (aggrDays × avgDailyVolume)
  final double projectedProfit; // projectedVolume × priceDiff

  const ImportRow({
    required this.typeId,
    required this.typeName,
    required this.sourcePrice,
    required this.destPrice,
    required this.importPrice,
    required this.priceDiff,
    required this.margin,
    this.sourceOrderCount = 0,
    this.destOrderCount = 0,
    this.destRemainingVolume = 0,
    this.projectedVolume = 0,
    this.projectedProfit = 0,
  });
}
