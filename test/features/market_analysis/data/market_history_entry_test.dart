import 'package:flutter_test/flutter_test.dart';

import 'package:eve_ntt/features/market_analysis/data/market_history_entry.dart';

void main() {
  group('MarketHistoryEntry', () {
    test('can be created', () {
      final entry = MarketHistoryEntry(
        date: DateTime(2024, 1, 1),
        average: 5.5,
        highest: 6.0,
        lowest: 5.0,
        orderCount: 100,
        volume: 10000,
      );
      expect(entry.date, DateTime(2024, 1, 1));
      expect(entry.average, 5.5);
      expect(entry.highest, 6.0);
      expect(entry.lowest, 5.0);
      expect(entry.orderCount, 100);
      expect(entry.volume, 10000);
    });

    test('can be created from JSON', () {
      final json = {
        'date': '2024-01-01',
        'average': 5.5,
        'highest': 6.0,
        'lowest': 5.0,
        'order_count': 100,
        'volume': 10000,
      };
      final entry = MarketHistoryEntry.fromJson(json);
      expect(entry.date.year, 2024);
      expect(entry.date.month, 1);
      expect(entry.date.day, 1);
      expect(entry.average, 5.5);
      expect(entry.highest, 6.0);
      expect(entry.lowest, 5.0);
      expect(entry.orderCount, 100);
      expect(entry.volume, 10000);
    });

    test('fromJson handles integer volume', () {
      final json = {
        'date': '2024-01-01',
        'average': 5.5,
        'highest': 6.0,
        'lowest': 5.0,
        'order_count': 100,
        'volume': 10000,
      };
      final entry = MarketHistoryEntry.fromJson(json);
      expect(entry.volume, 10000);
    });
  });
}
