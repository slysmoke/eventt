import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import 'package:eve_ntt/features/shell/presentation/app_route.dart';

void main() {
  group('AppRoute enum', () {
    test('has all expected routes', () {
      expect(AppRoute.values, hasLength(9));
      expect(AppRoute.values, contains(AppRoute.dashboard));
      expect(AppRoute.values, contains(AppRoute.marketBrowser));
      expect(AppRoute.values, contains(AppRoute.marketAnalysis));
      expect(AppRoute.values, contains(AppRoute.orders));
      expect(AppRoute.values, contains(AppRoute.marginTool));
      expect(AppRoute.values, contains(AppRoute.corporations));
      expect(AppRoute.values, contains(AppRoute.assets));
      expect(AppRoute.values, contains(AppRoute.transactions));
      expect(AppRoute.values, contains(AppRoute.journal));
    });
  });

  group('AppRoute labels', () {
    test('dashboard has correct label', () {
      expect(AppRoute.dashboard.label, 'Dashboard');
    });

    test('marketBrowser has correct label', () {
      expect(AppRoute.marketBrowser.label, 'Market Browser');
    });

    test('marketAnalysis has correct label', () {
      expect(AppRoute.marketAnalysis.label, 'Market Analysis');
    });

    test('orders has correct label', () {
      expect(AppRoute.orders.label, 'My Orders');
    });

    test('marginTool has correct label', () {
      expect(AppRoute.marginTool.label, 'Margin Tool');
    });

    test('corporations has correct label', () {
      expect(AppRoute.corporations.label, 'Corporations');
    });

    test('assets has correct label', () {
      expect(AppRoute.assets.label, 'Assets');
    });

    test('transactions has correct label', () {
      expect(AppRoute.transactions.label, 'Transactions');
    });

    test('journal has correct label', () {
      expect(AppRoute.journal.label, 'Journal');
    });
  });

  group('AppRoute icons', () {
    test('dashboard has correct icon', () {
      expect(AppRoute.dashboard.icon, Icons.dashboard_outlined);
    });

    test('marketBrowser has correct icon', () {
      expect(AppRoute.marketBrowser.icon, Icons.store_outlined);
    });

    test('marketAnalysis has correct icon', () {
      expect(AppRoute.marketAnalysis.icon, Icons.candlestick_chart_outlined);
    });

    test('orders has correct icon', () {
      expect(AppRoute.orders.icon, Icons.list_alt_outlined);
    });

    test('marginTool has correct icon', () {
      expect(AppRoute.marginTool.icon, Icons.calculate_outlined);
    });

    test('corporations has correct icon', () {
      expect(AppRoute.corporations.icon, Icons.business_outlined);
    });

    test('assets has correct icon', () {
      expect(AppRoute.assets.icon, Icons.inventory_2_outlined);
    });

    test('transactions has correct icon', () {
      expect(AppRoute.transactions.icon, Icons.receipt_long_outlined);
    });

    test('journal has correct icon', () {
      expect(AppRoute.journal.icon, Icons.account_balance_wallet_outlined);
    });
  });

  group('AppRoute selectedIcons', () {
    test('dashboard has correct selectedIcon', () {
      expect(AppRoute.dashboard.selectedIcon, Icons.dashboard);
    });

    test('marketBrowser has correct selectedIcon', () {
      expect(AppRoute.marketBrowser.selectedIcon, Icons.store);
    });

    test('marketAnalysis has correct selectedIcon', () {
      expect(AppRoute.marketAnalysis.selectedIcon, Icons.candlestick_chart);
    });

    test('orders has correct selectedIcon', () {
      expect(AppRoute.orders.selectedIcon, Icons.list_alt);
    });

    test('marginTool has correct selectedIcon', () {
      expect(AppRoute.marginTool.selectedIcon, Icons.calculate);
    });

    test('corporations has correct selectedIcon', () {
      expect(AppRoute.corporations.selectedIcon, Icons.business);
    });

    test('assets has correct selectedIcon', () {
      expect(AppRoute.assets.selectedIcon, Icons.inventory_2);
    });

    test('transactions has correct selectedIcon', () {
      expect(AppRoute.transactions.selectedIcon, Icons.receipt_long);
    });

    test('journal has correct selectedIcon', () {
      expect(AppRoute.journal.selectedIcon, Icons.account_balance_wallet);
    });
  });

  group('CurrentRouteNotifier', () {
    test('defaults to dashboard', () {
      final container = ProviderContainer();
      addTearDown(container.dispose);
      expect(container.read(currentRouteProvider), AppRoute.dashboard);
    });

    test('can navigate to different route', () {
      final container = ProviderContainer();
      addTearDown(container.dispose);
      container.read(currentRouteProvider.notifier).go(AppRoute.orders);
      expect(container.read(currentRouteProvider), AppRoute.orders);
    });
  });

  group('navGroups', () {
    test('has three groups', () {
      expect(navGroups, hasLength(3));
    });

    test('Trading group has correct routes', () {
      final trading = navGroups[0];
      expect(trading.label, 'Trading');
      expect(
        trading.routes,
        [
          AppRoute.dashboard,
          AppRoute.marketBrowser,
          AppRoute.marketAnalysis,
          AppRoute.orders,
          AppRoute.marginTool,
        ],
      );
    });

    test('Organizations group has correct routes', () {
      final orgs = navGroups[1];
      expect(orgs.label, 'Organizations');
      expect(orgs.routes, [AppRoute.corporations]);
    });

    test('Wallet group has correct routes', () {
      final wallet = navGroups[2];
      expect(wallet.label, 'Wallet');
      expect(
        wallet.routes,
        [
          AppRoute.assets,
          AppRoute.transactions,
          AppRoute.journal,
        ],
      );
    });
  });
}
