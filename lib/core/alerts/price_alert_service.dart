import 'dart:io';

import 'package:drift/drift.dart';

import '../database/app_database.dart';
import '../esi/esi_client.dart';
import '../../features/market_browser/data/market_order_repository.dart'
    show fixedMarketRegions;

/// Service that manages price alerts and checks them against live market data.
class PriceAlertService {
  final AppDatabase _db;
  final EsiClient _esi;

  PriceAlertService({required AppDatabase db, required EsiClient esi})
      : _db = db,
        _esi = esi;

  /// Watch all active (non-triggered) alerts.
  Stream<List<PriceAlert>> watchAlerts() {
    return (_db.select(_db.priceAlerts)
          ..where((t) => t.triggered.equals(false))
          ..orderBy([(t) => OrderingTerm(expression: t.createdAt)]))
        .watch();
  }

  /// Add a new price alert.
  Future<void> addAlert({
    required int typeId,
    required int regionId,
    required double targetPrice,
    required String condition, // 'above' or 'below'
  }) async {
    await _db.into(_db.priceAlerts).insert(
          PriceAlertsCompanion.insert(
            typeId: typeId,
            regionId: regionId,
            targetPrice: targetPrice,
            condition: condition,
            createdAt: DateTime.now(),
          ),
        );
  }

  /// Delete an alert.
  Future<void> deleteAlert(int id) async {
    await (_db.delete(_db.priceAlerts)..where((t) => t.id.equals(id))).go();
  }

  /// Mark an alert as triggered.
  Future<void> markTriggered(int id) async {
    await (_db.update(_db.priceAlerts)..where((t) => t.id.equals(id)))
        .write(PriceAlertsCompanion(triggered: const Value(true)));
  }

  /// Check all active alerts against current market prices.
  /// Returns list of triggered alert messages.
  Future<List<String>> checkAlerts() async {
    final alerts = await (_db.select(_db.priceAlerts)
          ..where((t) => t.triggered.equals(false)))
        .get();

    if (alerts.isEmpty) return [];

    final triggered = <String>[];

    // Group alerts by typeId+regionId to minimize API calls
    final groups = <String, List<PriceAlert>>{};
    for (final alert in alerts) {
      final key = '${alert.typeId}_${alert.regionId}';
      (groups[key] ??= []).add(alert);
    }

    for (final entry in groups.entries) {
      final parts = entry.key.split('_');
      final typeId = int.parse(parts[0]);
      final regionId = int.parse(parts[1]);
      final alertList = entry.value;

      try {
        final effectiveRegion = fixedMarketRegions[typeId] ?? regionId;
        final response = await _esi.get(
          '/markets/$effectiveRegion/orders/',
          queryParameters: {'type_id': typeId, 'order_type': 'sell'},
        );

        if (response.statusCode == 200 && response.data is List) {
          final orders = response.data as List;
          if (orders.isEmpty) continue;

          // Find best sell price (lowest)
          final bestPrice = orders
              .map((o) => (o['price'] as num).toDouble())
              .reduce((a, b) => a < b ? a : b);

          for (final alert in alertList) {
            bool hit = false;
            if (alert.condition == 'above' && bestPrice >= alert.targetPrice) {
              hit = true;
            } else if (alert.condition == 'below' &&
                bestPrice <= alert.targetPrice) {
              hit = true;
            }

            if (hit) {
              await markTriggered(alert.id);
              final direction = alert.condition == 'above' ? 'above' : 'below';
              triggered.add(
                'Type #$typeId: price is $direction ${alert.targetPrice.toStringAsFixed(2)} ISK '
                '(current: ${bestPrice.toStringAsFixed(2)} ISK)',
              );
            }
          }
        }
      } catch (_) {
        // Skip failed API calls
      }
    }

    return triggered;
  }

  /// Show desktop notification for triggered alerts.
  void showNotification(String message) {
    if (Platform.isLinux) {
      Process.run('notify-send', [
        'EVE NTT — Price Alert',
        message,
        '-i',
        'dialog-information',
      ]);
    } else if (Platform.isWindows) {
      // Windows toast notification via PowerShell
      Process.run('powershell', [
        '-Command',
        '[Windows.UI.Notifications.ToastNotificationManager, Windows.UI.Notifications, ContentType = WindowsRuntime] | Out-Null',
      ]);
    } else if (Platform.isMacOS) {
      Process.run('osascript', [
        '-e',
        'display notification "$message" with title "EVE NTT — Price Alert"',
      ]);
    }
  }
}
