import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../assets/presentation/assets_screen.dart';
import '../../dashboard/presentation/dashboard_screen.dart';
import '../../journal/presentation/journal_screen.dart';
import '../../margin_tool/presentation/margin_tool_screen.dart';
import '../../market_analysis/presentation/market_analysis_screen.dart';
import '../../market_browser/presentation/market_browser_screen.dart';
import '../../orders/presentation/orders_screen.dart';
import '../../transactions/presentation/transactions_screen.dart';

enum AppRoute {
  dashboard,
  marketBrowser,
  marketAnalysis,
  orders,
  marginTool,
  assets,
  transactions,
  journal,
}

final currentRouteProvider =
    NotifierProvider<CurrentRouteNotifier, AppRoute>(
  CurrentRouteNotifier.new,
);

class CurrentRouteNotifier extends Notifier<AppRoute> {
  @override
  AppRoute build() => AppRoute.dashboard;

  void go(AppRoute route) => state = route;
}

extension AppRouteX on AppRoute {
  String get label => switch (this) {
        AppRoute.dashboard => 'Dashboard',
        AppRoute.marketBrowser => 'Market Browser',
        AppRoute.marketAnalysis => 'Market Analysis',
        AppRoute.orders => 'My Orders',
        AppRoute.marginTool => 'Margin Tool',
        AppRoute.assets => 'Assets',
        AppRoute.transactions => 'Transactions',
        AppRoute.journal => 'Journal',
      };

  IconData get icon => switch (this) {
        AppRoute.dashboard => Icons.dashboard_outlined,
        AppRoute.marketBrowser => Icons.store_outlined,
        AppRoute.marketAnalysis => Icons.candlestick_chart_outlined,
        AppRoute.orders => Icons.list_alt_outlined,
        AppRoute.marginTool => Icons.calculate_outlined,
        AppRoute.assets => Icons.inventory_2_outlined,
        AppRoute.transactions => Icons.receipt_long_outlined,
        AppRoute.journal => Icons.account_balance_wallet_outlined,
      };

  IconData get selectedIcon => switch (this) {
        AppRoute.dashboard => Icons.dashboard,
        AppRoute.marketBrowser => Icons.store,
        AppRoute.marketAnalysis => Icons.candlestick_chart,
        AppRoute.orders => Icons.list_alt,
        AppRoute.marginTool => Icons.calculate,
        AppRoute.assets => Icons.inventory_2,
        AppRoute.transactions => Icons.receipt_long,
        AppRoute.journal => Icons.account_balance_wallet,
      };

  Widget get screen => switch (this) {
        AppRoute.dashboard => const DashboardScreen(),
        AppRoute.marketBrowser => const MarketBrowserScreen(),
        AppRoute.marketAnalysis => const MarketAnalysisScreen(),
        AppRoute.orders => const OrdersScreen(),
        AppRoute.marginTool => const MarginToolScreen(),
        AppRoute.assets => const AssetsScreen(),
        AppRoute.transactions => const TransactionsScreen(),
        AppRoute.journal => const JournalScreen(),
      };
}

/// Grouped navigation structure for the sidebar.
const navGroups = [
  _NavGroup(label: 'Trading', routes: [
    AppRoute.dashboard,
    AppRoute.marketBrowser,
    AppRoute.marketAnalysis,
    AppRoute.orders,
    AppRoute.marginTool,
  ]),
  _NavGroup(label: 'Wallet', routes: [
    AppRoute.assets,
    AppRoute.transactions,
    AppRoute.journal,
  ]),
];

class _NavGroup {
  final String label;
  final List<AppRoute> routes;
  const _NavGroup({required this.label, required this.routes});
}
