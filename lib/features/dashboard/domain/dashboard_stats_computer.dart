import 'dashboard_stats.dart';
import 'wallet_journal_entry.dart';

class DashboardStatsComputer {
  DashboardStatsComputer._();

  static DashboardStats compute({
    required double walletBalance,
    required List<WalletJournalEntry> entries,
    required DateTime now,
  }) {
    final today = DateTime.utc(now.year, now.month, now.day);
    final cutoff7d = today.subtract(const Duration(days: 6));
    final cutoff30d = today.subtract(const Duration(days: 29));

    double netProfit1d = 0, netProfit7d = 0, netProfit30d = 0;
    double taxes30d = 0, brokerFees30d = 0;
    final dailyMap = <DateTime, double>{};

    for (final e in entries) {
      final day = DateTime.utc(e.date.year, e.date.month, e.date.day);

      switch (e.refType) {
        case 'market_transaction':
          if (!day.isBefore(cutoff30d)) {
            netProfit30d += e.amount;
            dailyMap[day] = (dailyMap[day] ?? 0) + e.amount;
            if (!day.isBefore(cutoff7d)) netProfit7d += e.amount;
            if (!day.isBefore(today)) netProfit1d += e.amount;
          }
        case 'transaction_tax':
          if (!day.isBefore(cutoff30d)) taxes30d += e.amount.abs();
        case 'broker_fee':
          if (!day.isBefore(cutoff30d)) brokerFees30d += e.amount.abs();
      }
    }

    final daily30d = List.generate(
      30,
      (i) {
        final date = cutoff30d.add(Duration(days: i));
        return DailyPnl(date: date, netPnl: dailyMap[date] ?? 0);
      },
    );

    return DashboardStats(
      walletBalance: walletBalance,
      netProfit1d: netProfit1d,
      netProfit7d: netProfit7d,
      netProfit30d: netProfit30d,
      taxes30d: taxes30d,
      brokerFees30d: brokerFees30d,
      daily30d: daily30d,
    );
  }
}
