import 'package:flutter_test/flutter_test.dart';

import 'package:eve_ntt/features/market_analysis/domain/import_row.dart';

void main() {
  group('ImportRow', () {
    test('can be created with required fields', () {
      const row = ImportRow(
        typeId: 34,
        typeName: 'Tritanium',
        sourcePrice: 5.0,
        destPrice: 7.0,
        importPrice: 5.5,
        priceDiff: 1.5,
        margin: 21.43,
      );
      expect(row.typeId, 34);
      expect(row.typeName, 'Tritanium');
      expect(row.sourcePrice, 5.0);
      expect(row.destPrice, 7.0);
      expect(row.importPrice, 5.5);
      expect(row.priceDiff, 1.5);
      expect(row.margin, 21.43);
    });

    test('has default values for optional fields', () {
      const row = ImportRow(
        typeId: 34,
        typeName: 'Tritanium',
        sourcePrice: 5.0,
        destPrice: 7.0,
        importPrice: 5.5,
        priceDiff: 1.5,
        margin: 21.43,
      );
      expect(row.sourceOrderCount, 0);
      expect(row.destOrderCount, 0);
      expect(row.destRemainingVolume, 0);
      expect(row.projectedVolume, 0);
      expect(row.projectedProfit, 0);
    });

    test('can be created with all fields', () {
      const row = ImportRow(
        typeId: 34,
        typeName: 'Tritanium',
        sourcePrice: 5.0,
        destPrice: 7.0,
        importPrice: 5.5,
        priceDiff: 1.5,
        margin: 21.43,
        sourceOrderCount: 100,
        destOrderCount: 50,
        destRemainingVolume: 10000,
        projectedVolume: 5000,
        projectedProfit: 7500.0,
      );
      expect(row.sourceOrderCount, 100);
      expect(row.destOrderCount, 50);
      expect(row.destRemainingVolume, 10000);
      expect(row.projectedVolume, 5000);
      expect(row.projectedProfit, 7500.0);
    });
  });
}
