import 'package:flutter_test/flutter_test.dart';

import 'package:eve_ntt/features/market_analysis/domain/market_indicators.dart';

void main() {
  group('MarketIndicators.computeSma()', () {
    test('returns null during warmup period', () {
      // With period=3, first two values have null SMA
      final values = [1.0, 2.0, 3.0, 4.0, 5.0];
      final sma = MarketIndicators.computeSma(values, period: 3);

      expect(sma[0], isNull);
      expect(sma[1], isNull);
      expect(sma[2], closeTo(2.0, 0.001)); // (1+2+3)/3
    });

    test('computes correct SMA values', () {
      final values = [2.0, 4.0, 6.0, 8.0, 10.0];
      final sma = MarketIndicators.computeSma(values, period: 3);

      expect(sma[2], closeTo(4.0, 0.001)); // (2+4+6)/3
      expect(sma[3], closeTo(6.0, 0.001)); // (4+6+8)/3
      expect(sma[4], closeTo(8.0, 0.001)); // (6+8+10)/3
    });

    test('returns list of same length as input', () {
      final values = List.generate(10, (i) => i.toDouble());
      final sma = MarketIndicators.computeSma(values, period: 5);
      expect(sma.length, values.length);
    });

    test('all null when list shorter than period', () {
      final values = [1.0, 2.0];
      final sma = MarketIndicators.computeSma(values, period: 5);
      expect(sma.every((v) => v == null), isTrue);
    });

    test('empty input returns empty output', () {
      expect(MarketIndicators.computeSma([], period: 3), isEmpty);
    });
  });

  group('MarketIndicators.computeMacd()', () {
    // Build a flat series: MACD should converge to ~0
    final flat = List.filled(30, 100.0);

    test('returns same-length result as input', () {
      final result = MarketIndicators.computeMacd(flat);
      expect(result.macd.length, flat.length);
      expect(result.signal.length, flat.length);
      expect(result.histogram.length, flat.length);
    });

    test('MACD of flat series converges to zero', () {
      final result = MarketIndicators.computeMacd(flat);
      // After warmup (slow period), MACD should be near zero
      final lastMacd = result.macd.last;
      if (lastMacd != null) {
        expect(lastMacd.abs(), lessThan(1e-6));
      }
    });

    test('histogram = macd - signal', () {
      final values = List.generate(30, (i) => i.toDouble());
      final result = MarketIndicators.computeMacd(values);

      for (var i = 0; i < result.macd.length; i++) {
        final m = result.macd[i];
        final s = result.signal[i];
        final h = result.histogram[i];
        if (m != null && s != null) {
          expect(h, isNotNull);
          expect(h!, closeTo(m - s, 1e-9));
        } else {
          expect(h, isNull);
        }
      }
    });

    test('null during warmup period', () {
      // fast=5, slow=15: first (slow-1)=14 values should have null MACD
      final result = MarketIndicators.computeMacd(flat);
      for (var i = 0; i < 14; i++) {
        expect(result.macd[i], isNull,
            reason: 'index $i should be null during warmup');
      }
    });

    test('empty input returns empty output', () {
      final result = MarketIndicators.computeMacd([]);
      expect(result.macd, isEmpty);
      expect(result.signal, isEmpty);
      expect(result.histogram, isEmpty);
    });
  });
}
