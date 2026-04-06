// Table schema tests - drift tables are schema definitions, not much to unit test.
// The actual table behavior is tested through integration tests in app_database_test.dart.

import 'package:flutter_test/flutter_test.dart';

import 'package:eve_ntt/core/database/tables/app_settings.dart';
import 'package:eve_ntt/core/database/tables/characters.dart';
import 'package:eve_ntt/core/database/tables/esi_cache.dart';
import 'package:eve_ntt/core/database/tables/price_alerts.dart';

void main() {
  group('Table schema sanity checks', () {
    test('AppSettings table can be instantiated', () {
      final table = AppSettings();
      expect(table, isNotNull);
    });

    test('Characters table can be instantiated', () {
      final table = Characters();
      expect(table, isNotNull);
    });

    test('EsiCache table can be instantiated', () {
      final table = EsiCache();
      expect(table, isNotNull);
    });

    test('PriceAlerts table can be instantiated', () {
      final table = PriceAlerts();
      expect(table, isNotNull);
    });
  });
}
