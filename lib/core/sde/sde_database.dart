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

  /// Named regions sorted alphabetically, for the region selector.
  List<MapRegion> getRegions() {
    return _db
        .select(
          'SELECT regionID, regionName '
          'FROM mapRegions '
          'WHERE regionName IS NOT NULL '
          'ORDER BY regionName',
        )
        .map(MapRegion.fromRow)
        .toList();
  }

  void close() => _db.dispose();
}
