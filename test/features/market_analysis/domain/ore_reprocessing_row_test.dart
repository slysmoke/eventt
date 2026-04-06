import 'package:flutter_test/flutter_test.dart';

import 'package:eve_ntt/features/market_analysis/domain/ore_reprocessing_row.dart';

void main() {
  group('ReprocessedMaterial', () {
    test('can be created', () {
      const material = ReprocessedMaterial(
        typeId: 34,
        quantity: 100,
      );
      expect(material.typeId, 34);
      expect(material.quantity, 100);
    });
  });

  group('OreReprocessingInfo', () {
    test('can be created', () {
      const info = OreReprocessingInfo(
        oreTypeId: 1230,
        portionSize: 100,
        materials: [
          ReprocessedMaterial(typeId: 34, quantity: 100),
          ReprocessedMaterial(typeId: 35, quantity: 50),
        ],
      );
      expect(info.oreTypeId, 1230);
      expect(info.portionSize, 100);
      expect(info.materials, hasLength(2));
    });
  });

  group('OreReprocessingRow', () {
    test('can be created', () {
      const row = OreReprocessingRow(
        oreTypeId: 1230,
        oreName: 'Veldspar',
        volume: 10000,
        totalProfit: 50000.0,
        totalCost: 40000.0,
        difference: 10000.0,
        margin: 25.0,
      );
      expect(row.oreTypeId, 1230);
      expect(row.oreName, 'Veldspar');
      expect(row.volume, 10000);
      expect(row.totalProfit, 50000.0);
      expect(row.totalCost, 40000.0);
      expect(row.difference, 10000.0);
      expect(row.margin, 25.0);
    });
  });
}
