import 'package:flutter_test/flutter_test.dart';
import 'package:sqlite3/sqlite3.dart';

import 'package:eve_ntt/core/sde/sde_database.dart';

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

  group('SdeDatabase.getTypesByIds()', () {
    setUp(() {
      raw.execute('''
        INSERT INTO invTypes
          (typeID, groupID, typeName, volume, published, marketGroupID)
        VALUES
          (34, 1, 'Tritanium', 0.01, 1, 18),
          (35, 1, 'Pyerite',   0.02, 1, 18)
      ''');
    });

    test('returns map of typeId → InvType for found IDs', () {
      final result = sde.getTypesByIds([34, 35]);
      expect(result.length, 2);
      expect(result[34]?.typeName, 'Tritanium');
      expect(result[35]?.typeName, 'Pyerite');
    });

    test('ignores IDs not in the database', () {
      final result = sde.getTypesByIds([34, 9999]);
      expect(result.length, 1);
      expect(result[9999], isNull);
    });

    test('returns empty map for empty input', () {
      expect(sde.getTypesByIds([]), isEmpty);
    });

    test('parses volume correctly', () {
      final result = sde.getTypesByIds([34]);
      expect(result[34]?.volume, closeTo(0.01, 0.001));
    });
  });

  group('SdeDatabase.getTopLevelMarketGroups()', () {
    test('returns only groups with no parent, sorted by name', () {
      raw.execute('''
        INSERT INTO invMarketGroups VALUES (1, NULL, 'Ships', NULL, 1),
                                           (2, NULL, 'Ammo',  NULL, 1),
                                           (3, 1,    'Frigates', NULL, 1)
      ''');

      final groups = sde.getTopLevelMarketGroups();

      expect(groups.length, 2);
      expect(groups[0].marketGroupName, 'Ammo');   // alphabetical
      expect(groups[1].marketGroupName, 'Ships');
      expect(groups.any((g) => g.marketGroupName == 'Frigates'), isFalse);
    });
  });

  group('SdeDatabase.getTypeIdsForGroupTree()', () {
    setUp(() {
      raw.execute('''
        INSERT INTO invMarketGroups VALUES
          (10, NULL, 'Ships',    NULL, 0),
          (11, 10,   'Frigates', NULL, 1),
          (12, 10,   'Cruisers', NULL, 1)
      ''');
      raw.execute('''
        INSERT INTO invTypes (typeID, groupID, typeName, volume, published, marketGroupID) VALUES
          (100, 1, 'Rifter',    10.0, 1, 11),
          (101, 1, 'Slasher',   10.0, 1, 11),
          (102, 1, 'Rupture',   10.0, 1, 12),
          (103, 1, 'Unpub',     10.0, 0, 11)
      ''');
    });

    test('returns type IDs for direct group', () {
      final ids = sde.getTypeIdsForGroupTree(11).toSet();
      expect(ids, containsAll([100, 101]));
      expect(ids.contains(103), isFalse); // unpublished
    });

    test('returns type IDs from all descendant groups', () {
      final ids = sde.getTypeIdsForGroupTree(10).toSet();
      expect(ids, containsAll([100, 101, 102]));
    });

    test('returns empty for group with no types', () {
      final ids = sde.getTypeIdsForGroupTree(9999);
      expect(ids, isEmpty);
    });
  });

  group('SdeDatabase.searchTypes()', () {
    setUp(() {
      raw.execute('''
        INSERT INTO invTypes
          (typeID, groupID, typeName, volume, published, marketGroupID)
        VALUES
          (34,  1, 'Tritanium',         0.01, 1, 18),
          (35,  1, 'Tritanium Ore',     0.01, 1, 18),
          (36,  1, 'Pyerite',           0.01, 1, 18),
          (37,  1, 'Unpublished Trit',  0.01, 0, 18)
      ''');
    });

    test('prefix match returns prefix results first', () {
      final results = sde.searchTypes('Trit');
      expect(results.map((t) => t.typeName).first, startsWith('Trit'));
    });

    test('returns only published types', () {
      final results = sde.searchTypes('Trit');
      expect(results.any((t) => t.typeName == 'Unpublished Trit'), isFalse);
    });

    test('substring match works', () {
      final results = sde.searchTypes('anium');
      expect(results.any((t) => t.typeName == 'Tritanium'), isTrue);
    });

    test('respects limit', () {
      final results = sde.searchTypes('Trit', limit: 1);
      expect(results.length, 1);
    });

    test('empty query returns empty list', () {
      expect(sde.searchTypes(''), isEmpty);
    });

    test('no match returns empty list', () {
      expect(sde.searchTypes('ZZZNOTFOUND'), isEmpty);
    });
  });

  group('SdeDatabase.getScrapmetalInfo()', () {
    setUp(() {
      raw.execute('''
        INSERT INTO invGroups (groupID, categoryID, groupName, published) VALUES
          (53,   7,  'Projectile Weapon', 1),
          (1850, 25, 'Veldspar',          1)
      ''');
      raw.execute('''
        INSERT INTO invTypes
          (typeID, groupID, typeName, volume, published, marketGroupID, portionSize)
        VALUES
          (12760, 53,   '150mm AutoCannon I', 5.0, 1,    100, 1),
          (1230,  1850, 'Veldspar',           0.1, 1,    200, 333),
          (99998, 53,   'Unpublished Module', 1.0, 0,    100, 1),
          (99999, 53,   'No Market Module',   1.0, 1,    NULL, 1)
      ''');
      raw.execute('''
        INSERT INTO invTypeMaterials (typeID, materialTypeID, quantity) VALUES
          (12760, 34, 20),
          (12760, 35, 10),
          (1230,  34, 400),
          (99998, 34, 5),
          (99999, 34, 5)
      ''');
    });

    test('returns modules with materials, excludes ores (category 25)', () {
      final info = sde.getScrapmetalInfo();
      expect(info.containsKey(12760), isTrue);
      expect(info.containsKey(1230), isFalse);
    });

    test('excludes unpublished items', () {
      final info = sde.getScrapmetalInfo();
      expect(info.containsKey(99998), isFalse);
    });

    test('excludes items without market group', () {
      final info = sde.getScrapmetalInfo();
      expect(info.containsKey(99999), isFalse);
    });

    test('parses materials correctly', () {
      final info = sde.getScrapmetalInfo();
      final cannon = info[12760]!;
      expect(cannon.materials.length, 2);
      expect(cannon.materials.firstWhere((m) => m.typeId == 34).quantity, 20);
      expect(cannon.materials.firstWhere((m) => m.typeId == 35).quantity, 10);
    });

    test('uses portionSize from invTypes', () {
      final info = sde.getScrapmetalInfo();
      expect(info[12760]!.portionSize, 1);
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
      marketGroupID INTEGER,
      portionSize INTEGER
    )
  ''');

  db.execute('''
    CREATE TABLE invGroups (
      groupID INTEGER PRIMARY KEY,
      categoryID INTEGER,
      groupName TEXT,
      published INTEGER
    )
  ''');

  db.execute('''
    CREATE TABLE invTypeMaterials (
      typeID INTEGER NOT NULL,
      materialTypeID INTEGER NOT NULL,
      quantity INTEGER NOT NULL,
      PRIMARY KEY (typeID, materialTypeID)
    )
  ''');

  db.execute('''
    CREATE TABLE mapRegions (
      regionID INTEGER PRIMARY KEY,
      regionName TEXT
    )
  ''');
}
