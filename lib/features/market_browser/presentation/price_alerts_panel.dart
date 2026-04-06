import 'package:drift/drift.dart' show Value;
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../core/alerts/price_alert_provider.dart';
import '../../../core/alerts/price_alert_service.dart';
import '../../../core/database/app_database.dart';
import '../../../core/esi/esi_provider.dart';
import '../../../core/database/database_provider.dart';
import '../../../core/sde/sde_provider.dart';

/// Panel to view, edit, and delete price alerts.
class PriceAlertsPanel extends ConsumerWidget {
  const PriceAlertsPanel({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final alertsAsync = ref.watch(priceAlertsStreamProvider);
    final theme = Theme.of(context);

    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Padding(
          padding: const EdgeInsets.fromLTRB(16, 12, 16, 8),
          child: Row(
            children: [
              Icon(Icons.notifications_active,
                  color: theme.colorScheme.primary, size: 20),
              const SizedBox(width: 8),
              Text(
                'Price Alerts',
                style: theme.textTheme.titleSmall?.copyWith(
                  color: theme.colorScheme.primary,
                  fontWeight: FontWeight.w600,
                ),
              ),
              const Spacer(),
              IconButton(
                icon: const Icon(Icons.refresh, size: 18),
                tooltip: 'Check alerts now',
                onPressed: () => _checkAlertsNow(ref, context),
              ),
            ],
          ),
        ),
        const Divider(height: 1),
        Expanded(
          child: alertsAsync.when(
            loading: () => const Center(child: CircularProgressIndicator()),
            error: (e, _) => Center(
              child: Text('Error: $e',
                  style: TextStyle(color: theme.colorScheme.error)),
            ),
            data: (alerts) {
              if (alerts.isEmpty) {
                return Center(
                  child: Column(
                    mainAxisSize: MainAxisSize.min,
                    children: [
                      Icon(Icons.notifications_off,
                          color: theme.colorScheme.onSurfaceVariant, size: 48),
                      const SizedBox(height: 12),
                      Text(
                        'No active alerts',
                        style: theme.textTheme.bodyMedium?.copyWith(
                          color: theme.colorScheme.onSurfaceVariant,
                        ),
                      ),
                      const SizedBox(height: 4),
                      Text(
                        'Long-press an order to create one',
                        style: theme.textTheme.labelSmall?.copyWith(
                          color: theme.colorScheme.onSurfaceVariant,
                        ),
                      ),
                    ],
                  ),
                );
              }

              return ListView.separated(
                itemCount: alerts.length,
                separatorBuilder: (_, __) => const Divider(height: 1),
                itemBuilder: (context, index) {
                  final alert = alerts[index];
                  return _AlertTile(alert: alert);
                },
              );
            },
          ),
        ),
      ],
    );
  }

  void _checkAlertsNow(WidgetRef ref, BuildContext context) {
    final db = ref.watch(databaseProvider);
    final esi = ref.watch(esiClientProvider);
    final service = PriceAlertService(db: db, esi: esi);

    service.checkAlerts().then((triggered) {
      if (triggered.isNotEmpty) {
        for (final msg in triggered) {
          service.showNotification(msg);
        }
        if (context.mounted) {
          ScaffoldMessenger.of(context).showSnackBar(
            SnackBar(
              content: Text('${triggered.length} alert(s) triggered!'),
              duration: const Duration(seconds: 3),
            ),
          );
        }
      } else {
        if (context.mounted) {
          ScaffoldMessenger.of(context).showSnackBar(
            const SnackBar(
              content: Text('No alerts triggered'),
              duration: Duration(seconds: 2),
            ),
          );
        }
      }
    });
  }
}

class _AlertTile extends ConsumerWidget {
  final PriceAlert alert;
  const _AlertTile({required this.alert});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final theme = Theme.of(context);
    final db = ref.watch(sdeDatabaseProvider);
    final typeName = db?.getTypesByIds([alert.typeId])[alert.typeId]?.typeName ?? 'Type #${alert.typeId}';
    final direction = alert.condition == 'above' ? '≥' : '≤';
    final dirColor = alert.condition == 'above' ? Colors.green : Colors.red;

    return ListTile(
      dense: true,
      contentPadding: const EdgeInsets.symmetric(horizontal: 16, vertical: 2),
      title: Text(
        typeName,
        style: theme.textTheme.bodySmall?.copyWith(
          fontWeight: FontWeight.w500,
        ),
        maxLines: 1,
        overflow: TextOverflow.ellipsis,
      ),
      subtitle: Text(
        '$direction ${alert.targetPrice.toStringAsFixed(2)} ISK',
        style: theme.textTheme.labelSmall?.copyWith(
          color: dirColor,
          fontWeight: FontWeight.w600,
        ),
      ),
      trailing: Row(
        mainAxisSize: MainAxisSize.min,
        children: [
          IconButton(
            icon: const Icon(Icons.edit, size: 18),
            tooltip: 'Edit',
            onPressed: () => _showEditDialog(context, ref),
          ),
          IconButton(
            icon: const Icon(Icons.delete, size: 18),
            tooltip: 'Delete',
            color: theme.colorScheme.error,
            onPressed: () {
              final db = ref.read(databaseProvider);
              (db.delete(db.priceAlerts)..where((t) => t.id.equals(alert.id))).go();
            },
          ),
        ],
      ),
    );
  }

  void _showEditDialog(BuildContext context, WidgetRef ref) {
    showDialog(
      context: context,
      builder: (ctx) => _EditAlertDialog(alert: alert),
    );
  }
}

class _EditAlertDialog extends ConsumerStatefulWidget {
  final PriceAlert alert;
  const _EditAlertDialog({required this.alert});

  @override
  ConsumerState<_EditAlertDialog> createState() => _EditAlertDialogState();
}

class _EditAlertDialogState extends ConsumerState<_EditAlertDialog> {
  late final TextEditingController _controller;
  late String _condition;

  @override
  void initState() {
    super.initState();
    _controller = TextEditingController(text: widget.alert.targetPrice.toStringAsFixed(2));
    _condition = widget.alert.condition;
  }

  @override
  void dispose() {
    _controller.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return AlertDialog(
      title: const Text('Edit Price Alert'),
      content: Column(
        mainAxisSize: MainAxisSize.min,
        children: [
          TextField(
            controller: _controller,
            keyboardType: const TextInputType.numberWithOptions(decimal: true),
            decoration: const InputDecoration(
              labelText: 'Target Price (ISK)',
              border: OutlineInputBorder(),
            ),
          ),
          const SizedBox(height: 12),
          Wrap(
            spacing: 8,
            alignment: WrapAlignment.start,
            crossAxisAlignment: WrapCrossAlignment.center,
            children: [
              const Text('Alert when:'),
              ChoiceChip(
                label: const Text('Below'),
                selected: _condition == 'below',
                onSelected: (v) => setState(() => _condition = 'below'),
              ),
              ChoiceChip(
                label: const Text('Above'),
                selected: _condition == 'above',
                onSelected: (v) => setState(() => _condition = 'above'),
              ),
            ],
          ),
        ],
      ),
      actions: [
        TextButton(
          onPressed: () => Navigator.of(context).pop(),
          child: const Text('Cancel'),
        ),
        FilledButton(
          onPressed: () {
            final price = double.tryParse(_controller.text);
            if (price != null && price > 0) {
              final db = ref.read(databaseProvider);
              (db.update(db.priceAlerts)..where((t) => t.id.equals(widget.alert.id)))
                  .write(PriceAlertsCompanion(
                targetPrice: Value(price),
                condition: Value(_condition),
              ));
              Navigator.of(context).pop();
            }
          },
          child: const Text('Save'),
        ),
      ],
    );
  }
}
