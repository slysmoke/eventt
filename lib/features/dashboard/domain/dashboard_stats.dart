class DailyPnl {
  final DateTime date;
  final double netPnl;

  const DailyPnl({required this.date, required this.netPnl});
}

class DashboardStats {
  final double walletBalance;
  final double netProfit1d;
  final double netProfit7d;
  final double netProfit30d;
  final double taxes30d;
  final double brokerFees30d;

  /// 30 daily P&L points, sorted oldest → newest.
  final List<DailyPnl> daily30d;

  const DashboardStats({
    required this.walletBalance,
    required this.netProfit1d,
    required this.netProfit7d,
    required this.netProfit30d,
    required this.taxes30d,
    required this.brokerFees30d,
    required this.daily30d,
  });

  static final empty = DashboardStats(
    walletBalance: 0,
    netProfit1d: 0,
    netProfit7d: 0,
    netProfit30d: 0,
    taxes30d: 0,
    brokerFees30d: 0,
    daily30d: List.generate(
      30,
      (i) => DailyPnl(
        date: DateTime.now().subtract(Duration(days: 29 - i)),
        netPnl: 0,
      ),
    ),
  );
}
