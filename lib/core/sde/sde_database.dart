import 'package:sqlite3/sqlite3.dart';

import 'sde_models.dart';

/// Read-only wrapper around the EVE SDE SQLite database (eve.db).
///
/// Opened via [SdeDatabase.open] for production or [SdeDatabase.fromDatabase]
/// for testing (pass an in-memory db with SDE-schema tables pre-created).
class SdeDatabase {
  final Database _db;

  SdeDatabase._(this._db);

  factory SdeDatabase.open(String path) =>
      SdeDatabase._(sqlite3.open(path));

  /// For tests: inject a pre-created database instance.
  factory SdeDatabase.fromDatabase(Database db) => SdeDatabase._(db);

  /// All market groups, used to build the navigation tree.
  List<InvMarketGroup> getMarketGroups() {
    return _db
        .select(
          'SELECT marketGroupID, parentGroupID, marketGroupName, hasTypes '
          'FROM invMarketGroups',
        )
        .map(InvMarketGroup.fromRow)
        .toList();
  }

  /// Published item types belonging to [marketGroupId], sorted by name.
  List<InvType> getTypesForGroup(int marketGroupId) {
    return _db
        .select(
          'SELECT typeID, typeName, volume '
          'FROM invTypes '
          'WHERE marketGroupID = ? AND published = 1 '
          'ORDER BY typeName',
          [marketGroupId],
        )
        .map(InvType.fromRow)
        .toList();
  }

  /// Returns a {typeId → InvType} map for the given IDs.
  Map<int, InvType> getTypesByIds(List<int> typeIds) {
    if (typeIds.isEmpty) return {};
    final placeholders = List.filled(typeIds.length, '?').join(',');
    final rows = _db.select(
      'SELECT typeID, typeName, volume FROM invTypes WHERE typeID IN ($placeholders)',
      typeIds,
    );
    return {
      for (final row in rows) (row['typeID'] as int): InvType.fromRow(row)
    };
  }

  /// Searches published types by name. Returns prefix matches first, then other
  /// substring matches, alphabetically within each group. Empty [query] → [].
  List<InvType> searchTypes(String query, {int limit = 20}) {
    if (query.isEmpty) return [];
    final pattern = '%${query.replaceAll('%', '\\%').replaceAll('_', '\\_')}%';
    final prefixPattern =
        '${query.replaceAll('%', '\\%').replaceAll('_', '\\_')}%';
    return _db
        .select(
          'SELECT typeID, typeName, volume '
          'FROM invTypes '
          'WHERE published = 1 AND typeName LIKE ? ESCAPE \'\\\' '
          'ORDER BY CASE WHEN typeName LIKE ? ESCAPE \'\\\' THEN 0 ELSE 1 END, '
          '         typeName '
          'LIMIT ?',
          [pattern, prefixPattern, limit],
        )
        .map(InvType.fromRow)
        .toList();
  }

  /// Top-level market groups (parentGroupId == null), sorted by name.
  List<InvMarketGroup> getTopLevelMarketGroups() {
    return _db
        .select(
          'SELECT marketGroupID, parentGroupID, marketGroupName, hasTypes '
          'FROM invMarketGroups '
          'WHERE parentGroupID IS NULL '
          'ORDER BY marketGroupName',
        )
        .map(InvMarketGroup.fromRow)
        .toList();
  }

  /// All published type IDs in [rootGroupId] and all its descendant groups.
  List<int> getTypeIdsForGroupTree(int rootGroupId) {
    final rows = _db.select('''
      WITH RECURSIVE tree(id) AS (
        SELECT marketGroupID FROM invMarketGroups WHERE marketGroupID = ?
        UNION ALL
        SELECT g.marketGroupID FROM invMarketGroups g JOIN tree ON g.parentGroupID = tree.id
      )
      SELECT typeID FROM invTypes
      WHERE marketGroupID IN (SELECT id FROM tree) AND published = 1
    ''', [rootGroupId]);
    return rows.map((r) => r['typeID'] as int).toList();
  }

  /// All published type IDs in the SDE.
  List<int> getAllPublishedTypeIds() {
    final rows = _db.select(
      'SELECT typeID FROM invTypes WHERE published = 1',
    );
    return rows.map((r) => r['typeID'] as int).toList();
  }

  /// Named regions sorted alphabetically, for the region selector.
  /// Only returns regions with IDs starting with 10 (standard EVE regions).
  List<MapRegion> getRegions() {
    return _db
        .select(
          'SELECT regionID, regionName '
          'FROM mapRegions '
          'WHERE regionName IS NOT NULL '
          '  AND regionID >= 10000000 AND regionID < 11000000 '
          'ORDER BY regionName',
        )
        .map(MapRegion.fromRow)
        .toList();
  }

  /// Resolves a location ID to a station or solar system name.
  /// Returns null if not found (e.g. player structure).
  String? getLocationName(int locationId) {
    // Try stations first (staStations)
    var row = _db.select(
      'SELECT stationName FROM staStations WHERE stationID = ?',
      [locationId],
    ).firstOrNull;
    if (row != null) return row['stationName'] as String?;

    // Try solar systems (mapSolarSystems)
    row = _db.select(
      'SELECT solarSystemName FROM mapSolarSystems WHERE solarSystemID = ?',
      [locationId],
    ).firstOrNull;
    if (row != null) return row['solarSystemName'] as String?;

    return null;
  }

  /// Batch resolves location IDs to station/system names.
  /// Returns a map of locationId -> name.
  Map<int, String> getLocationNames(List<int> locationIds) {
    if (locationIds.isEmpty) return {};

    final result = <int, String>{};
    final placeholders = locationIds.map((_) => '?').join(',');

    // Stations
    final stationRows = _db.select(
      'SELECT stationID, stationName FROM staStations WHERE stationID IN ($placeholders)',
      locationIds,
    );
    for (final row in stationRows) {
      result[row['stationID'] as int] = row['stationName'] as String;
    }

    // Solar systems (only for IDs not found as stations)
    final unresolvedIds = locationIds.where((id) => !result.containsKey(id)).toList();
    if (unresolvedIds.isNotEmpty) {
      final sysPlaceholders = unresolvedIds.map((_) => '?').join(',');
      final sysRows = _db.select(
        'SELECT solarSystemID, solarSystemName FROM mapSolarSystems WHERE solarSystemID IN ($sysPlaceholders)',
        unresolvedIds,
      );
      for (final row in sysRows) {
        result[row['solarSystemID'] as int] = row['solarSystemName'] as String;
      }
    }

    return result;
  }

  /// Returns scrapmetal items (non-ore published items with market groups)
  /// that have reprocessing material data in invTypeMaterials.
  /// Key = typeId, excludes asteroid category (categoryID = 25).
  Map<int, ScrapmetalInfo> getScrapmetalInfo() {
    final rows = _db.select('''
      SELECT m.typeID, m.materialTypeID, m.quantity,
             COALESCE(t.portionSize, 1) AS portionSize
      FROM invTypeMaterials m
      JOIN invTypes t ON t.typeID = m.typeID
      JOIN invGroups g ON g.groupID = t.groupID
      WHERE t.published = 1
        AND t.marketGroupID IS NOT NULL
        AND g.categoryID != 25
      ORDER BY m.typeID, m.materialTypeID
    ''');

    final materialsMap = <int, List<ScrapmetalMaterial>>{};
    final portionSizeMap = <int, int>{};

    for (final row in rows) {
      final typeId = row['typeID'] as int;
      portionSizeMap[typeId] = row['portionSize'] as int? ?? 1;
      (materialsMap[typeId] ??= []).add(ScrapmetalMaterial(
        typeId: row['materialTypeID'] as int,
        quantity: row['quantity'] as int,
      ));
    }

    return {
      for (final typeId in materialsMap.keys)
        typeId: ScrapmetalInfo(
          typeId: typeId,
          portionSize: portionSizeMap[typeId]!,
          materials: materialsMap[typeId]!,
        ),
    };
  }

  void close() => _db.dispose();
}
