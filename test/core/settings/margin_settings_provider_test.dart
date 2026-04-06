import 'package:flutter_test/flutter_test.dart';

import 'package:eve_ntt/core/settings/margin_settings_provider.dart';
import 'package:eve_ntt/core/margin/margin_calculator.dart';

void main() {
  group('MarginSettings', () {
    test('has correct defaults', () {
      const settings = MarginSettings();
      expect(settings.brokerFeePct, 2.0);
      expect(settings.salesTaxPct, 3.6);
      expect(settings.includeBuyBrokerFee, isFalse);
      expect(settings.marketLogDir, isNull);
    });

    test('can be created with custom values', () {
      const settings = MarginSettings(
        brokerFeePct: 1.5,
        salesTaxPct: 4.0,
        includeBuyBrokerFee: true,
        marketLogDir: '/path/to/logs',
      );
      expect(settings.brokerFeePct, 1.5);
      expect(settings.salesTaxPct, 4.0);
      expect(settings.includeBuyBrokerFee, isTrue);
      expect(settings.marketLogDir, '/path/to/logs');
    });

    test('marginParams returns correct values', () {
      const settings = MarginSettings(
        brokerFeePct: 1.5,
        salesTaxPct: 4.0,
        includeBuyBrokerFee: true,
      );
      final params = settings.marginParams;
      expect(params.brokerFeePct, 1.5);
      expect(params.salesTaxPct, 4.0);
      expect(params.includeBuyBrokerFee, isTrue);
    });

    test('copyWith updates single field', () {
      const settings = MarginSettings();
      final updated = settings.copyWith(brokerFeePct: 1.0);
      expect(updated.brokerFeePct, 1.0);
      expect(updated.salesTaxPct, 3.6); // unchanged
    });

    test('copyWith with clearMarketLogDir sets marketLogDir to null', () {
      const settings = MarginSettings(marketLogDir: '/path');
      final updated = settings.copyWith(clearMarketLogDir: true);
      expect(updated.marketLogDir, isNull);
    });

    test('copyWith with marketLogDir updates value', () {
      const settings = MarginSettings();
      final updated = settings.copyWith(marketLogDir: '/new/path');
      expect(updated.marketLogDir, '/new/path');
    });

    test('copyWith with empty marketLogDir keeps existing value', () {
      const settings = MarginSettings(marketLogDir: '/path');
      final updated = settings.copyWith(marketLogDir: '/other');
      expect(updated.marketLogDir, '/other');
    });
  });
}
