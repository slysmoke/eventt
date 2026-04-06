import 'package:flutter_test/flutter_test.dart';

import 'package:eve_ntt/core/sde/sde_models.dart';
import 'package:sqlite3/sqlite3.dart';

void main() {
  group('InvMarketGroup', () {
    test('can be created with required fields', () {
      const group = InvMarketGroup(
        marketGroupId: 1,
        marketGroupName: 'Test Group',
        hasTypes: true,
      );
      expect(group.marketGroupId, 1);
      expect(group.marketGroupName, 'Test Group');
      expect(group.hasTypes, isTrue);
      expect(group.parentGroupId, isNull);
    });

    test('can be created from Row', () {
      final db = sqlite3.openInMemory();
      try {
        final row = db.select(
          "SELECT 1 as marketGroupID, NULL as parentGroupID, 'Test' as marketGroupName, 1 as hasTypes",
        ).first;
        final group = InvMarketGroup.fromRow(row);
        expect(group.marketGroupId, 1);
        expect(group.marketGroupName, 'Test');
        expect(group.hasTypes, isTrue);
        expect(group.parentGroupId, isNull);
      } finally {
        db.dispose();
      }
    });

    test('handles null marketGroupName', () {
      final db = sqlite3.openInMemory();
      try {
        final row = db.select(
          "SELECT 1 as marketGroupID, NULL as parentGroupID, NULL as marketGroupName, 0 as hasTypes",
        ).first;
        final group = InvMarketGroup.fromRow(row);
        expect(group.marketGroupName, '(unnamed)');
      } finally {
        db.dispose();
      }
    });
  });

  group('InvType', () {
    test('can be created with required fields', () {
      const type = InvType(
        typeId: 34,
        typeName: 'Tritanium',
      );
      expect(type.typeId, 34);
      expect(type.typeName, 'Tritanium');
      expect(type.volume, isNull);
    });

    test('can be created with volume', () {
      const type = InvType(
        typeId: 34,
        typeName: 'Tritanium',
        volume: 0.01,
      );
      expect(type.volume, 0.01);
    });

    test('can be created from Row', () {
      final db = sqlite3.openInMemory();
      try {
        final row = db.select(
          "SELECT 34 as typeID, 'Tritanium' as typeName, 0.01 as volume",
        ).first;
        final type = InvType.fromRow(row);
        expect(type.typeId, 34);
        expect(type.typeName, 'Tritanium');
        expect(type.volume, 0.01);
      } finally {
        db.dispose();
      }
    });

    test('handles null typeName', () {
      final db = sqlite3.openInMemory();
      try {
        final row = db.select("SELECT 34 as typeID, NULL as typeName, NULL as volume").first;
        final type = InvType.fromRow(row);
        expect(type.typeName, '(unnamed)');
        expect(type.volume, isNull);
      } finally {
        db.dispose();
      }
    });
  });

  group('ScrapmetalMaterial', () {
    test('can be created', () {
      const material = ScrapmetalMaterial(
        typeId: 34,
        quantity: 100,
      );
      expect(material.typeId, 34);
      expect(material.quantity, 100);
    });
  });

  group('ScrapmetalInfo', () {
    test('can be created', () {
      const info = ScrapmetalInfo(
        typeId: 1234,
        portionSize: 100,
        materials: [
          ScrapmetalMaterial(typeId: 34, quantity: 100),
          ScrapmetalMaterial(typeId: 35, quantity: 50),
        ],
      );
      expect(info.typeId, 1234);
      expect(info.portionSize, 100);
      expect(info.materials, hasLength(2));
    });
  });

  group('MapRegion', () {
    test('can be created', () {
      const region = MapRegion(
        regionId: 10000002,
        regionName: 'The Forge',
      );
      expect(region.regionId, 10000002);
      expect(region.regionName, 'The Forge');
    });

    test('can be created from Row', () {
      final db = sqlite3.openInMemory();
      try {
        final row = db.select(
          "SELECT 10000002 as regionID, 'The Forge' as regionName",
        ).first;
        final region = MapRegion.fromRow(row);
        expect(region.regionId, 10000002);
        expect(region.regionName, 'The Forge');
      } finally {
        db.dispose();
      }
    });
  });
}
