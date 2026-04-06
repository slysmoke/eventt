import 'package:flutter_test/flutter_test.dart';

import 'package:eve_ntt/features/market_analysis/domain/scrapmetal_reprocessing_row.dart';

void main() {
  group('ScrapmetalReprocessingRow', () {
    test('can be created', () {
      const row = ScrapmetalReprocessingRow(
        typeId: 1234,
        typeName: 'Damaged Drone Module',
        volume: 1000,
        totalProfit: 50000.0,
        totalCost: 40000.0,
        difference: 10000.0,
        margin: 25.0,
      );
      expect(row.typeId, 1234);
      expect(row.typeName, 'Damaged Drone Module');
      expect(row.volume, 1000);
      expect(row.totalProfit, 50000.0);
      expect(row.totalCost, 40000.0);
      expect(row.difference, 10000.0);
      expect(row.margin, 25.0);
    });
  });
}
