/// Result of reprocessing one unit of ore.
class ReprocessedMaterial {
  final int typeId;
  final int quantity;

  const ReprocessedMaterial({
    required this.typeId,
    required this.quantity,
  });
}

/// Reprocessing info for one ore type.
class OreReprocessingInfo {
  final int oreTypeId;
  final int portionSize; // how many units to reprocess at once
  final List<ReprocessedMaterial> materials;

  const OreReprocessingInfo({
    required this.oreTypeId,
    required this.portionSize,
    required this.materials,
  });
}

/// One row in the Ore Reprocessing table.
class OreReprocessingRow {
  final int oreTypeId;
  final String oreName;
  final int volume;           // total ore volume processed
  final double totalProfit;   // ISK from selling minerals
  final double totalCost;     // ISK spent buying ore
  final double difference;    // totalProfit - totalCost
  final double margin;        // (difference / totalCost) × 100

  const OreReprocessingRow({
    required this.oreTypeId,
    required this.oreName,
    required this.volume,
    required this.totalProfit,
    required this.totalCost,
    required this.difference,
    required this.margin,
  });
}
