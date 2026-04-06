import 'package:flutter_test/flutter_test.dart';

import 'package:eve_ntt/features/market_analysis/domain/import_params.dart';

void main() {
  group('PriceType enum', () {
    test('has sell and buy values', () {
      expect(PriceType.values, contains(PriceType.sell));
      expect(PriceType.values, contains(PriceType.buy));
    });
  });

  group('ImportParams', () {
    test('has correct defaults', () {
      const params = ImportParams();
      expect(params.srcPriceType, PriceType.sell);
      expect(params.dstPriceType, PriceType.buy);
      expect(params.pricePerM3, 0);
      expect(params.collateralPct, 0);
      expect(params.collateralPriceType, PriceType.buy);
      expect(params.srcPriceMod, 0);
      expect(params.minMarginPct, 0);
      expect(params.maxMarginPct, isNull);
      expect(params.hideEmptySrcSell, isFalse);
      expect(params.volumePercentile, 0);
      expect(params.analysisDays, 180);
      expect(params.aggrDays, 7);
    });

    test('can be created with custom values', () {
      const params = ImportParams(
        srcPriceType: PriceType.buy,
        dstPriceType: PriceType.sell,
        pricePerM3: 100.0,
        collateralPct: 10.0,
        srcPriceMod: 5.0,
        minMarginPct: 5.0,
        maxMarginPct: 50.0,
        hideEmptySrcSell: true,
        volumePercentile: 0.05,
        analysisDays: 90,
        aggrDays: 14,
      );
      expect(params.srcPriceType, PriceType.buy);
      expect(params.dstPriceType, PriceType.sell);
      expect(params.pricePerM3, 100.0);
      expect(params.collateralPct, 10.0);
      expect(params.srcPriceMod, 5.0);
      expect(params.minMarginPct, 5.0);
      expect(params.maxMarginPct, 50.0);
      expect(params.hideEmptySrcSell, isTrue);
      expect(params.volumePercentile, 0.05);
      expect(params.analysisDays, 90);
      expect(params.aggrDays, 14);
    });

    test('copyWith updates single field', () {
      const params = ImportParams();
      final updated = params.copyWith(pricePerM3: 50.0);
      expect(updated.pricePerM3, 50.0);
      expect(params.srcPriceType, PriceType.sell); // unchanged
    });

    test('copyWith with clearMaxMargin sets maxMarginPct to null', () {
      const params = ImportParams(maxMarginPct: 50.0);
      final updated = params.copyWith(clearMaxMargin: true);
      expect(updated.maxMarginPct, isNull);
    });

    test('copyWith preserves other fields', () {
      const params = ImportParams(
        pricePerM3: 100.0,
        analysisDays: 90,
      );
      final updated = params.copyWith(aggrDays: 14);
      expect(updated.pricePerM3, 100.0);
      expect(updated.analysisDays, 90);
      expect(updated.aggrDays, 14);
    });
  });
}
