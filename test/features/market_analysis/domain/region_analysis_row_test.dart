import 'package:flutter_test/flutter_test.dart';

import 'package:eve_ntt/features/market_analysis/domain/region_analysis_row.dart';

void main() {
  group('RegionAnalysisRow', () {
    test('can be created', () {
      const row = RegionAnalysisRow(
        typeId: 34,
        typeName: 'Tritanium',
        srcPrice: 5.0,
        dstPrice: 7.0,
        difference: 2.0,
        volume: 10000,
        buyOrderCount: 50,
        sellOrderCount: 100,
        margin: 28.57,
        sellBuyout: 50000.0,
      );
      expect(row.typeId, 34);
      expect(row.typeName, 'Tritanium');
      expect(row.srcPrice, 5.0);
      expect(row.dstPrice, 7.0);
      expect(row.difference, 2.0);
      expect(row.volume, 10000);
      expect(row.buyOrderCount, 50);
      expect(row.sellOrderCount, 100);
      expect(row.margin, 28.57);
      expect(row.sellBuyout, 50000.0);
    });
  });
}
