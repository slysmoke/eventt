import 'package:eventt/features/dashboard/domain/dashboard_stats_computer.dart';
import 'package:eventt/features/dashboard/domain/wallet_journal_entry.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  // Reference: 2024-01-15 12:00 UTC — "now"
  final now = DateTime.utc(2024, 1, 15, 12, 0, 0);
  final today = DateTime.utc(2024, 1, 15);

  WalletJournalEntry e({
    int id = 1,
    String refType = 'market_transaction',
    double amount = 100.0,
    required DateTime date,
  }) =>
      WalletJournalEntry(id: id, refType: refType, amount: amount, date: date);

  group('DashboardStatsComputer.compute()', () {
    test('empty entries — all zeros, balance passed through', () {
      final s = DashboardStatsComputer.compute(
          walletBalance: 999.0, entries: [], now: now);
      expect(s.walletBalance, 999.0);
      expect(s.netProfit1d, 0.0);
      expect(s.netProfit7d, 0.0);
      expect(s.netProfit30d, 0.0);
      expect(s.taxes30d, 0.0);
      expect(s.brokerFees30d, 0.0);
    });

    test('daily30d always has exactly 30 entries oldest→newest', () {
      final s = DashboardStatsComputer.compute(
          walletBalance: 0, entries: [], now: now);
      expect(s.daily30d.length, 30);
      expect(s.daily30d.first.date, today.subtract(const Duration(days: 29)));
      expect(s.daily30d.last.date, today);
    });

    test('market_transaction today — counted in 1d, 7d, 30d', () {
      final s = DashboardStatsComputer.compute(
        walletBalance: 0,
        entries: [e(amount: 200.0, date: today.add(const Duration(hours: 1)))],
        now: now,
      );
      expect(s.netProfit1d, 200.0);
      expect(s.netProfit7d, 200.0);
      expect(s.netProfit30d, 200.0);
    });

    test('market_transaction 5 days ago — in 7d and 30d, not 1d', () {
      final s = DashboardStatsComputer.compute(
        walletBalance: 0,
        entries: [e(amount: 300.0, date: today.subtract(const Duration(days: 5)))],
        now: now,
      );
      expect(s.netProfit1d, 0.0);
      expect(s.netProfit7d, 300.0);
      expect(s.netProfit30d, 300.0);
    });

    test('market_transaction 15 days ago — in 30d only', () {
      final s = DashboardStatsComputer.compute(
        walletBalance: 0,
        entries: [e(amount: 400.0, date: today.subtract(const Duration(days: 15)))],
        now: now,
      );
      expect(s.netProfit1d, 0.0);
      expect(s.netProfit7d, 0.0);
      expect(s.netProfit30d, 400.0);
    });

    test('market_transaction 31 days ago — not counted', () {
      final s = DashboardStatsComputer.compute(
        walletBalance: 0,
        entries: [e(amount: 500.0, date: today.subtract(const Duration(days: 31)))],
        now: now,
      );
      expect(s.netProfit30d, 0.0);
    });

    test('negative market_transaction (buy cost) subtracts from profit', () {
      final s = DashboardStatsComputer.compute(
        walletBalance: 0,
        entries: [
          e(id: 1, amount: 1000.0, date: today),
          e(id: 2, amount: -600.0, date: today),
        ],
        now: now,
      );
      expect(s.netProfit1d, closeTo(400.0, 0.01));
    });

    test('transaction_tax — adds to taxes30d (positive), not to profit', () {
      final s = DashboardStatsComputer.compute(
        walletBalance: 0,
        entries: [e(refType: 'transaction_tax', amount: -50.0, date: today)],
        now: now,
      );
      expect(s.taxes30d, 50.0);
      expect(s.netProfit30d, 0.0);
    });

    test('broker_fee — adds to brokerFees30d (positive)', () {
      final s = DashboardStatsComputer.compute(
        walletBalance: 0,
        entries: [e(refType: 'broker_fee', amount: -30.0, date: today)],
        now: now,
      );
      expect(s.brokerFees30d, 30.0);
    });

    test('transaction_tax outside 30d — not counted', () {
      final s = DashboardStatsComputer.compute(
        walletBalance: 0,
        entries: [
          e(
              refType: 'transaction_tax',
              amount: -100.0,
              date: today.subtract(const Duration(days: 31)))
        ],
        now: now,
      );
      expect(s.taxes30d, 0.0);
    });

    test('daily P&L aggregated per day', () {
      final s = DashboardStatsComputer.compute(
        walletBalance: 0,
        entries: [
          e(id: 1, amount: 100.0, date: today.add(const Duration(hours: 8))),
          e(id: 2, amount: 200.0, date: today.add(const Duration(hours: 14))),
          e(id: 3, amount: -50.0, date: today.add(const Duration(hours: 16))),
        ],
        now: now,
      );
      expect(s.daily30d.last.date, today);
      expect(s.daily30d.last.netPnl, closeTo(250.0, 0.01));
    });

    test('daily P&L across multiple days', () {
      final s = DashboardStatsComputer.compute(
        walletBalance: 0,
        entries: [
          e(id: 1, amount: 100.0, date: today.subtract(const Duration(days: 2))),
          e(id: 2, amount: 200.0, date: today.subtract(const Duration(days: 1))),
          e(id: 3, amount: 300.0, date: today),
        ],
        now: now,
      );
      final last3 = s.daily30d.sublist(27);
      expect(last3[0].netPnl, 100.0);
      expect(last3[1].netPnl, 200.0);
      expect(last3[2].netPnl, 300.0);
    });

    test('entries from multiple characters are aggregated', () {
      final s = DashboardStatsComputer.compute(
        walletBalance: 0,
        entries: [
          e(id: 1, amount: 500.0, date: today),
          e(id: 2, amount: 500.0, date: today),
        ],
        now: now,
      );
      expect(s.netProfit1d, 1000.0);
    });
  });
}
