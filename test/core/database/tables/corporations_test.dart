// Table schema tests - drift tables are schema definitions, not much to unit test.
// The actual table behavior is tested through integration tests in app_database_test.dart.

import 'package:flutter_test/flutter_test.dart';

import 'package:eve_ntt/core/database/tables/corporations.dart';

void main() {
  group('Corporations table', () {
    test('can be instantiated', () {
      final table = Corporations();
      expect(table, isNotNull);
    });
  });
}
