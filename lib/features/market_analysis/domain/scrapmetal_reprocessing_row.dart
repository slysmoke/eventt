/// One row in the Scrapmetal Reprocessing analysis table.
class ScrapmetalReprocessingRow {
  final int typeId;
  final String typeName;
  final int volume;         // total units processed
  final double totalProfit; // ISK from selling minerals
  final double totalCost;   // ISK spent buying items
  final double difference;  // totalProfit − totalCost
  final double margin;      // (difference / totalCost) × 100

  const ScrapmetalReprocessingRow({
    required this.typeId,
    required this.typeName,
    required this.volume,
    required this.totalProfit,
    required this.totalCost,
    required this.difference,
    required this.margin,
  });
}
