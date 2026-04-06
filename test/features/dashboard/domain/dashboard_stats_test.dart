import 'package:flutter_test/flutter_test.dart';

import 'package:eve_ntt/features/dashboard/domain/dashboard_stats.dart';

void main() {
  group('DailyPnl', () {
    test('can be created', () {
      final pnl = DailyPnl(
        date: DateTime(2024, 1, 1),
        netPnl: 1000.0,
      );
      expect(pnl.date, DateTime(2024, 1, 1));
      expect(pnl.netPnl, 1000.0);
    });
  });

  group('DashboardStats', () {
    test('can be created with values', () {
      final stats = DashboardStats(
        walletBalance: 1000000.0,
        netProfit1d: 5000.0,
        netProfit7d: 20000.0,
        netProfit30d: 50000.0,
        taxes30d: 5000.0,
        brokerFees30d: 2000.0,
        daily30d: [],
      );
      expect(stats.walletBalance, 1000000.0);
      expect(stats.netProfit1d, 5000.0);
      expect(stats.netProfit7d, 20000.0);
      expect(stats.netProfit30d, 50000.0);
      expect(stats.taxes30d, 5000.0);
      expect(stats.brokerFees30d, 2000.0);
      expect(stats.daily30d, isEmpty);
    });

    test('empty stats have zero values', () {
      final empty = DashboardStats.empty;
      expect(empty.walletBalance, 0);
      expect(empty.netProfit1d, 0);
      expect(empty.netProfit7d, 0);
      expect(empty.netProfit30d, 0);
      expect(empty.taxes30d, 0);
      expect(empty.brokerFees30d, 0);
    });

    test('empty stats have 30 daily P&L entries', () {
      final empty = DashboardStats.empty;
      expect(empty.daily30d, hasLength(30));
    });

    test('empty daily30d entries have zero P&L', () {
      final empty = DashboardStats.empty;
      for (final daily in empty.daily30d) {
        expect(daily.netPnl, 0);
      }
    });

    test('empty daily30d dates are sorted oldest to newest', () {
      final empty = DashboardStats.empty;
      for (var i = 0; i < empty.daily30d.length - 1; i++) {
        expect(
          empty.daily30d[i].date.isBefore(empty.daily30d[i + 1].date),
          isTrue,
        );
      }
    });
  });
}
