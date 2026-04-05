import 'package:sqlite3/sqlite3.dart';

class InvMarketGroup {
  final int marketGroupId;
  final int? parentGroupId;
  final String marketGroupName;
  final bool hasTypes;

  const InvMarketGroup({
    required this.marketGroupId,
    this.parentGroupId,
    required this.marketGroupName,
    required this.hasTypes,
  });

  factory InvMarketGroup.fromRow(Row row) => InvMarketGroup(
        marketGroupId: row['marketGroupID'] as int,
        parentGroupId: row['parentGroupID'] as int?,
        marketGroupName: (row['marketGroupName'] as String?) ?? '(unnamed)',
        hasTypes: (row['hasTypes'] as int?) == 1,
      );
}

class InvType {
  final int typeId;
  final String typeName;
  final double? volume;

  const InvType({
    required this.typeId,
    required this.typeName,
    this.volume,
  });

  factory InvType.fromRow(Row row) => InvType(
        typeId: row['typeID'] as int,
        typeName: (row['typeName'] as String?) ?? '(unnamed)',
        volume: row['volume'] as double?,
      );
}

/// One material produced when reprocessing a scrapmetal item.
class ScrapmetalMaterial {
  final int typeId;
  final int quantity;

  const ScrapmetalMaterial({required this.typeId, required this.quantity});
}

/// Reprocessing data for one scrapmetal item (from invTypeMaterials).
class ScrapmetalInfo {
  final int typeId;
  final int portionSize;
  final List<ScrapmetalMaterial> materials;

  const ScrapmetalInfo({
    required this.typeId,
    required this.portionSize,
    required this.materials,
  });
}

class MapRegion {
  final int regionId;
  final String regionName;

  const MapRegion({required this.regionId, required this.regionName});

  factory MapRegion.fromRow(Row row) => MapRegion(
        regionId: row['regionID'] as int,
        regionName: row['regionName'] as String,
      );
}
