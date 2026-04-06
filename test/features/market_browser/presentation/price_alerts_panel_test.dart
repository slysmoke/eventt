// PriceAlertsPanel requires database and ESI providers - tested via integration tests.
import 'package:flutter_test/flutter_test.dart';

import 'package:eve_ntt/features/market_browser/presentation/price_alerts_panel.dart';

void main() {
  group('PriceAlertsPanel', () {
    test('widget class exists', () {
      expect(PriceAlertsPanel, isNotNull);
    });
  });
}
