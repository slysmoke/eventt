import 'package:flutter_test/flutter_test.dart';
import 'package:sqlite3/sqlite3.dart';

import 'package:eventt/core/sde/sde_database.dart';

void main() {
  late Database raw;
  late SdeDatabase sde;

  setUp(() {
    raw = sqlite3.openInMemory();
    _createSdeTables(raw);
    sde = SdeDatabase.fromDatabase(raw);
  });

  tearDown(() {
    sde.close();
  });

  group('SdeDatabase.getMarketGroups()', () {
    test('returns all market groups', () {
      raw.execute('''
        INSERT INTO invMarketGroups VALUES (1, NULL, 'Ships', NULL, 1),
                                           (2, 1, 'Frigates', NULL, 1),
                                           (3, 1, 'Cruisers', NULL, 1)
      ''');

      final groups = sde.getMarketGroups();

      expect(groups.length, 3);
      expect(groups.map((g) => g.marketGroupId).toSet(), {1, 2, 3});
    });

    test('parses parentGroupId correctly', () {
      raw.execute(
          "INSERT INTO invMarketGroups VALUES (10, NULL, 'Root', NULL, 0)");
      raw.execute(
          "INSERT INTO invMarketGroups VALUES (11, 10, 'Child', NULL, 1)");

      final groups = sde.getMarketGroups();
      final root = groups.firstWhere((g) => g.marketGroupId == 10);
      final child = groups.firstWhere((g) => g.marketGroupId == 11);

      expect(root.parentGroupId, isNull);
      expect(child.parentGroupId, 10);
    });

    test('parses hasTypes flag', () {
      raw.execute(
          "INSERT INTO invMarketGroups VALUES (20, NULL, 'Has Items', NULL, 1)");
      raw.execute(
          "INSERT INTO invMarketGroups VALUES (21, NULL, 'No Items', NULL, 0)");

      final groups = sde.getMarketGroups();
      expect(groups.firstWhere((g) => g.marketGroupId == 20).hasTypes, isTrue);
      expect(
          groups.firstWhere((g) => g.marketGroupId == 21).hasTypes, isFalse);
    });
  });

  group('SdeDatabase.getTypesForGroup()', () {
    setUp(() {
      raw.execute('''
        INSERT INTO invTypes
          (typeID, groupID, typeName, volume, published, marketGroupID)
        VALUES
          (34, 1, 'Tritanium', 0.01, 1, 18),
          (35, 1, 'Pyerite', 0.01, 1, 18),
          (36, 1, 'Mexallon', 0.01, 0, 18),
          (37, 1, 'Isogen', 0.01, 1, 25)
      ''');
    });

    test('returns only published types for the given group', () {
      final types = sde.getTypesForGroup(18);
      expect(types.length, 2);
      expect(types.map((t) => t.typeName).toSet(), {'Tritanium', 'Pyerite'});
    });

    test('excludes other groups', () {
      final types = sde.getTypesForGroup(25);
      expect(types.length, 1);
      expect(types.first.typeName, 'Isogen');
    });

    test('returns empty list for unknown group', () {
      expect(sde.getTypesForGroup(9999), isEmpty);
    });

    test('parses volume correctly', () {
      final types = sde.getTypesForGroup(18);
      expect(types.first.volume, closeTo(0.01, 0.001));
    });
  });

  group('SdeDatabase.getRegions()', () {
    test('returns named regions sorted alphabetically', () {
      raw.execute('''
        INSERT INTO mapRegions (regionID, regionName) VALUES
          (10000002, 'The Forge'),
          (10000043, 'Domain'),
          (10000032, 'Sinq Laison'),
          (10000001, NULL)
      ''');

      final regions = sde.getRegions();

      expect(regions.length, 3); // NULL excluded
      expect(regions.first.regionName, 'Domain');
      expect(regions.map((r) => r.regionId).contains(10000001), isFalse);
    });
  });
}

void _createSdeTables(Database db) {
  db.execute('''
    CREATE TABLE invMarketGroups (
      marketGroupID INTEGER PRIMARY KEY,
      parentGroupID INTEGER,
      marketGroupName TEXT,
      description TEXT,
      hasTypes INTEGER
    )
  ''');

  db.execute('''
    CREATE TABLE invTypes (
      typeID INTEGER PRIMARY KEY,
      groupID INTEGER,
      typeName TEXT,
      volume REAL,
      published INTEGER,
      marketGroupID INTEGER
    )
  ''');

  db.execute('''
    CREATE TABLE mapRegions (
      regionID INTEGER PRIMARY KEY,
      regionName TEXT
    )
  ''');
}
