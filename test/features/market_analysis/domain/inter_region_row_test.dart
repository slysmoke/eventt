import 'package:flutter_test/flutter_test.dart';

import 'package:eve_ntt/features/market_analysis/domain/inter_region_row.dart';

void main() {
  group('InterRegionRow', () {
    test('can be created', () {
      const row = InterRegionRow(
        typeId: 34,
        typeName: 'Tritanium',
        srcRegionId: 10000002,
        srcRegionName: 'The Forge',
        srcBuyPrice: 4.5,
        srcSellPrice: 5.0,
        srcOrderCount: 100,
        srcSellBuyout: 50000.0,
        dstRegionId: 10000043,
        dstRegionName: 'Domain',
        dstBuyPrice: 6.5,
        dstSellPrice: 7.0,
        dstOrderCount: 80,
        dstSellBuyout: 70000.0,
        difference: 2.0,
        volume: 20000,
        margin: 28.57,
      );
      expect(row.typeId, 34);
      expect(row.typeName, 'Tritanium');
      expect(row.srcRegionId, 10000002);
      expect(row.srcRegionName, 'The Forge');
      expect(row.srcBuyPrice, 4.5);
      expect(row.srcSellPrice, 5.0);
      expect(row.srcOrderCount, 100);
      expect(row.srcSellBuyout, 50000.0);
      expect(row.dstRegionId, 10000043);
      expect(row.dstRegionName, 'Domain');
      expect(row.dstBuyPrice, 6.5);
      expect(row.dstSellPrice, 7.0);
      expect(row.dstOrderCount, 80);
      expect(row.dstSellBuyout, 70000.0);
      expect(row.difference, 2.0);
      expect(row.volume, 20000);
      expect(row.margin, 28.57);
    });
  });
}
