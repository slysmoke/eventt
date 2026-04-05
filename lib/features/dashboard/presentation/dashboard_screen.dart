import 'dart:math' as math;

import 'package:fl_chart/fl_chart.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../core/auth/auth_provider.dart';
import '../../../core/database/database_provider.dart';
import '../data/wallet_repository.dart';
import '../domain/dashboard_stats.dart';
import '../domain/dashboard_stats_computer.dart';
import '../domain/wallet_journal_entry.dart';

// ---------------------------------------------------------------------------
// Provider
// ---------------------------------------------------------------------------

final _dashboardStatsProvider = FutureProvider.autoDispose<DashboardStats>(
  (ref) async {
    final db = ref.watch(databaseProvider);
    final characters = await db.select(db.characters).get();

    if (characters.isEmpty) return DashboardStats.empty;

    final authService = ref.watch(eveAuthServiceProvider);
    final walletRepo = ref.watch(walletRepositoryProvider);

    double totalBalance = 0;
    final allEntries = <WalletJournalEntry>[];

    await Future.wait(characters.map((char) async {
      try {
        final token = await authService.getValidAccessToken(char.id);
        totalBalance += await walletRepo.fetchBalance(
            characterId: char.id, accessToken: token);
        final entries = await walletRepo.fetchJournal(
            characterId: char.id, accessToken: token);
        allEntries.addAll(entries);
      } catch (_) {
        // Skip characters without a valid token.
      }
    }));

    return DashboardStatsComputer.compute(
      walletBalance: totalBalance,
      entries: allEntries,
      now: DateTime.now(),
    );
  },
);

// ---------------------------------------------------------------------------
// Screen
// ---------------------------------------------------------------------------

class DashboardScreen extends ConsumerWidget {
  const DashboardScreen({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final statsAsync = ref.watch(_dashboardStatsProvider);

    return Scaffold(
      appBar: AppBar(
        title: const Text('Dashboard'),
        actions: [
          IconButton(
            icon: const Icon(Icons.refresh),
            tooltip: 'Refresh',
            onPressed: () => ref.invalidate(_dashboardStatsProvider),
          ),
        ],
      ),
      body: statsAsync.when(
        loading: () => const Center(child: CircularProgressIndicator()),
        error: (e, _) => Center(
          child: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              const Icon(Icons.error_outline, size: 48, color: Colors.red),
              const SizedBox(height: 8),
              Text('$e'),
              const SizedBox(height: 12),
              ElevatedButton(
                onPressed: () => ref.invalidate(_dashboardStatsProvider),
                child: const Text('Retry'),
              ),
            ],
          ),
        ),
        data: (stats) => _DashboardBody(stats: stats),
      ),
    );
  }
}

// ---------------------------------------------------------------------------
// Body
// ---------------------------------------------------------------------------

class _DashboardBody extends StatelessWidget {
  final DashboardStats stats;
  const _DashboardBody({required this.stats});

  @override
  Widget build(BuildContext context) {
    return SingleChildScrollView(
      padding: const EdgeInsets.all(16),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          _KpiGrid(stats: stats),
          const SizedBox(height: 24),
          Text('Daily P&L — last 30 days',
              style: Theme.of(context).textTheme.titleMedium),
          const SizedBox(height: 8),
          SizedBox(
            height: 220,
            child: _DailyPnlChart(daily: stats.daily30d),
          ),
        ],
      ),
    );
  }
}

// ---------------------------------------------------------------------------
// KPI grid
// ---------------------------------------------------------------------------

class _KpiGrid extends StatelessWidget {
  final DashboardStats stats;
  const _KpiGrid({required this.stats});

  @override
  Widget build(BuildContext context) {
    return Wrap(
      spacing: 12,
      runSpacing: 12,
      children: [
        _KpiCard(
          label: 'Wallet Balance',
          value: _fmtIsk(stats.walletBalance),
          color: Theme.of(context).colorScheme.onSurface,
          icon: Icons.account_balance_wallet,
        ),
        _KpiCard(
          label: 'P&L Today',
          value: _fmtIskSigned(stats.netProfit1d),
          color: _pnlColor(stats.netProfit1d),
          icon: Icons.today,
        ),
        _KpiCard(
          label: 'P&L 7 days',
          value: _fmtIskSigned(stats.netProfit7d),
          color: _pnlColor(stats.netProfit7d),
          icon: Icons.date_range,
        ),
        _KpiCard(
          label: 'P&L 30 days',
          value: _fmtIskSigned(stats.netProfit30d),
          color: _pnlColor(stats.netProfit30d),
          icon: Icons.calendar_month,
        ),
        _KpiCard(
          label: 'Taxes 30d',
          value: '−${_fmtIsk(stats.taxes30d)}',
          color: Colors.orange.shade400,
          icon: Icons.receipt,
        ),
        _KpiCard(
          label: 'Broker Fees 30d',
          value: '−${_fmtIsk(stats.brokerFees30d)}',
          color: Colors.orange.shade300,
          icon: Icons.store,
        ),
      ],
    );
  }
}

class _KpiCard extends StatelessWidget {
  final String label;
  final String value;
  final Color color;
  final IconData icon;

  const _KpiCard({
    required this.label,
    required this.value,
    required this.color,
    required this.icon,
  });

  @override
  Widget build(BuildContext context) {
    final cs = Theme.of(context).colorScheme;
    return SizedBox(
      width: 200,
      child: Card(
        child: Padding(
          padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 14),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Row(
                children: [
                  Icon(icon, size: 15, color: cs.onSurfaceVariant),
                  const SizedBox(width: 5),
                  Text(label,
                      style: Theme.of(context).textTheme.labelSmall?.copyWith(
                            color: cs.onSurfaceVariant,
                          )),
                ],
              ),
              const SizedBox(height: 8),
              Text(
                value,
                style: Theme.of(context).textTheme.titleMedium?.copyWith(
                      color: color,
                      fontWeight: FontWeight.bold,
                    ),
              ),
            ],
          ),
        ),
      ),
    );
  }
}

// ---------------------------------------------------------------------------
// Daily P&L bar chart
// ---------------------------------------------------------------------------

class _DailyPnlChart extends StatelessWidget {
  final List<DailyPnl> daily;
  const _DailyPnlChart({required this.daily});

  @override
  Widget build(BuildContext context) {
    final maxAbsM = daily.fold(0.0, (m, d) => math.max(m, d.netPnl.abs())) / 1e6;
    final interval = _niceIntervalM(maxAbsM);

    return BarChart(
      BarChartData(
        barGroups: daily.asMap().entries.map((e) {
          final d = e.value;
          return BarChartGroupData(
            x: e.key,
            barRods: [
              BarChartRodData(
                toY: d.netPnl / 1e6,
                color: d.netPnl >= 0
                    ? Colors.green.shade400
                    : Colors.red.shade400,
                width: 6,
                borderRadius: const BorderRadius.only(
                  topLeft: Radius.circular(2),
                  topRight: Radius.circular(2),
                ),
              ),
            ],
          );
        }).toList(),
        titlesData: FlTitlesData(
          topTitles: const AxisTitles(
              sideTitles: SideTitles(showTitles: false)),
          rightTitles: const AxisTitles(
              sideTitles: SideTitles(showTitles: false)),
          bottomTitles: AxisTitles(
            sideTitles: SideTitles(
              showTitles: true,
              interval: 1,
              reservedSize: 28,
              getTitlesWidget: (value, meta) {
                final i = value.toInt();
                if (i % 7 != 0 && i != 29) return const SizedBox.shrink();
                final d = daily[i].date;
                return Padding(
                  padding: const EdgeInsets.only(top: 4),
                  child: Text('${d.month}/${d.day}',
                      style: const TextStyle(fontSize: 10)),
                );
              },
            ),
          ),
          leftTitles: AxisTitles(
            sideTitles: SideTitles(
              showTitles: true,
              reservedSize: 52,
              interval: interval,
              getTitlesWidget: (value, meta) => Text(
                '${value.toStringAsFixed(0)}M',
                style: const TextStyle(fontSize: 10),
              ),
            ),
          ),
        ),
        gridData: FlGridData(
          show: true,
          drawVerticalLine: false,
          horizontalInterval: interval,
          getDrawingHorizontalLine: (y) => FlLine(
            color: y == 0 ? Colors.white30 : Colors.white12,
            strokeWidth: y == 0 ? 1 : 0.8,
            dashArray: y == 0 ? null : [4, 4],
          ),
        ),
        borderData: FlBorderData(show: false),
        barTouchData: BarTouchData(
          touchTooltipData: BarTouchTooltipData(
            getTooltipColor: (_) => Colors.blueGrey.shade800,
            getTooltipItem: (group, _, rod, __) {
              final d = daily[group.x];
              return BarTooltipItem(
                '${d.date.month}/${d.date.day}\n'
                '${_fmtIskSigned(d.netPnl)}',
                const TextStyle(color: Colors.white, fontSize: 12),
              );
            },
          ),
        ),
      ),
    );
  }
}

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

String _fmtIsk(double v) {
  final abs = v.abs();
  if (abs >= 1e12) return '${(v / 1e12).toStringAsFixed(2)}T';
  if (abs >= 1e9) return '${(v / 1e9).toStringAsFixed(2)}B';
  if (abs >= 1e6) return '${(v / 1e6).toStringAsFixed(2)}M';
  if (abs >= 1e3) return '${(v / 1e3).toStringAsFixed(2)}K';
  return v.toStringAsFixed(2);
}

String _fmtIskSigned(double v) =>
    '${v >= 0 ? "+" : ""}${_fmtIsk(v)}';

Color _pnlColor(double v) =>
    v >= 0 ? Colors.green.shade400 : Colors.red.shade400;

/// Returns a "nice" Y-axis interval in millions.
double _niceIntervalM(double maxAbsM) {
  if (maxAbsM == 0) return 1;
  final magnitude =
      math.pow(10.0, (math.log(maxAbsM * 2) / math.ln10).floor()).toDouble();
  for (final step in [1.0, 2.0, 5.0, 10.0]) {
    if (magnitude * step >= maxAbsM / 2) return magnitude * step;
  }
  return magnitude * 10;
}
