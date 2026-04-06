import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../core/auth/active_character_provider.dart';
import '../../../core/auth/auth_provider.dart';
import '../../../core/auth/eve_auth_service.dart';
import '../../../core/esi/esi_provider.dart';
import '../../../core/sde/sde_provider.dart';
import '../data/character_order_repository.dart';

final _ordersProvider = FutureProvider.autoDispose<List<CharacterOrder>>((ref) async {
  final characterAsync = ref.watch(activeCharacterProvider);
  final character = characterAsync.value;
  if (character == null) return [];

  final esi = ref.watch(esiClientProvider);
  final sde = ref.watch(sdeDatabaseProvider);
  final authService = ref.watch(eveAuthServiceProvider);

  try {
    final token = await authService.getValidAccessToken(character.id);
    final repo = CharacterOrderRepository(esi: esi, sde: sde);
    return repo.fetchOrders(characterId: character.id, accessToken: token);
  } catch (_) {
    return [];
  }
});

class OrdersScreen extends ConsumerWidget {
  const OrdersScreen({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final ordersAsync = ref.watch(_ordersProvider);
    final characterAsync = ref.watch(activeCharacterProvider);
    final character = characterAsync.value;

    return Scaffold(
      appBar: AppBar(
        title: const Text('My Orders'),
        actions: [
          IconButton(
            icon: const Icon(Icons.refresh),
            tooltip: 'Refresh',
            onPressed: () => ref.invalidate(_ordersProvider),
          ),
        ],
      ),
      body: character == null
          ? const _NoCharacterView()
          : ordersAsync.when(
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
                      onPressed: () => ref.invalidate(_ordersProvider),
                      child: const Text('Retry'),
                    ),
                  ],
                ),
              ),
              data: (orders) {
                if (orders.isEmpty) {
                  return const _EmptyOrdersView();
                }
                return _OrdersList(orders: orders);
              },
            ),
    );
  }
}

class _NoCharacterView extends StatelessWidget {
  const _NoCharacterView();

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    return Center(
      child: Column(
        mainAxisSize: MainAxisSize.min,
        children: [
          Icon(Icons.person_off,
              size: 64,
              color: theme.colorScheme.onSurface.withValues(alpha: 0.15)),
          const SizedBox(height: 16),
          Text(
            'No character selected',
            style: theme.textTheme.titleMedium?.copyWith(
              color: theme.colorScheme.onSurface.withValues(alpha: 0.4),
            ),
          ),
          const SizedBox(height: 8),
          Text(
            'Add a character via OAuth to see your orders.',
            textAlign: TextAlign.center,
            style: theme.textTheme.bodyMedium?.copyWith(
              color: theme.colorScheme.onSurface.withValues(alpha: 0.4),
            ),
          ),
        ],
      ),
    );
  }
}

class _EmptyOrdersView extends StatelessWidget {
  const _EmptyOrdersView();

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    return Center(
      child: Column(
        mainAxisSize: MainAxisSize.min,
        children: [
          Icon(Icons.list_alt,
              size: 64,
              color: theme.colorScheme.onSurface.withValues(alpha: 0.15)),
          const SizedBox(height: 16),
          Text(
            'No active orders',
            style: theme.textTheme.titleMedium?.copyWith(
              color: theme.colorScheme.onSurface.withValues(alpha: 0.4),
            ),
          ),
        ],
      ),
    );
  }
}

class _OrdersList extends StatefulWidget {
  final List<CharacterOrder> orders;
  const _OrdersList({required this.orders});

  @override
  State<_OrdersList> createState() => _OrdersListState();
}

class _OrdersListState extends State<_OrdersList> {
  bool _showBuyOrders = false;

  @override
  Widget build(BuildContext context) {
    final sellOrders = widget.orders.where((o) => !o.isBuyOrder).toList();
    final buyOrders = widget.orders.where((o) => o.isBuyOrder).toList();

    final orders = _showBuyOrders ? buyOrders : sellOrders;

    return Column(
      children: [
        // Toggle bar
        Padding(
          padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 8),
          child: Row(
            children: [
              Expanded(
                child: SegmentedButton<bool>(
                  segments: const [
                    ButtonSegment(
                      value: false,
                      label: Text('Sell'),
                      icon: Icon(Icons.sell, size: 16),
                    ),
                    ButtonSegment(
                      value: true,
                      label: Text('Buy'),
                      icon: Icon(Icons.add_shopping_cart, size: 16),
                    ),
                  ],
                  selected: {_showBuyOrders},
                  onSelectionChanged: (selected) {
                    setState(() => _showBuyOrders = selected.first);
                  },
                ),
              ),
              const SizedBox(width: 8),
              Text(
                '${orders.length} orders',
                style: Theme.of(context).textTheme.labelMedium?.copyWith(
                      color: Theme.of(context).colorScheme.onSurfaceVariant,
                    ),
              ),
            ],
          ),
        ),
        const Divider(height: 1),
        // Orders list
        Expanded(
          child: ListView.builder(
            itemCount: orders.length,
            itemBuilder: (context, index) {
              final order = orders[index];
              return _OrderTile(order: order);
            },
          ),
        ),
      ],
    );
  }
}

class _OrderTile extends StatelessWidget {
  final CharacterOrder order;
  const _OrderTile({required this.order});

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final typeName = order.typeName ?? 'Type #${order.typeId}';
    final volumePercent = order.volumeTotal > 0
        ? order.volumeRemain / order.volumeTotal
        : 0.0;

    return ListTile(
      dense: true,
      contentPadding: const EdgeInsets.symmetric(horizontal: 16, vertical: 4),
      leading: CircleAvatar(
        backgroundColor: order.isBuyOrder
            ? Colors.green.withValues(alpha: 0.15)
            : Colors.orange.withValues(alpha: 0.15),
        child: Icon(
          order.isBuyOrder ? Icons.add_shopping_cart : Icons.sell,
          color: order.isBuyOrder ? Colors.green : Colors.orange,
          size: 18,
        ),
      ),
      title: Text(
        typeName,
        style: theme.textTheme.bodySmall?.copyWith(fontWeight: FontWeight.w600),
        maxLines: 1,
        overflow: TextOverflow.ellipsis,
      ),
      subtitle: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          const SizedBox(height: 4),
          Row(
            children: [
              Text(
                '${_fmtIsk(order.price)} ISK',
                style: theme.textTheme.labelSmall?.copyWith(
                  fontWeight: FontWeight.bold,
                  color: theme.colorScheme.primary,
                ),
              ),
              const SizedBox(width: 12),
              Text(
                'Vol: ${order.volumeRemain}/${order.volumeTotal}',
                style: theme.textTheme.labelSmall?.copyWith(
                  color: theme.colorScheme.onSurfaceVariant,
                ),
              ),
            ],
          ),
          const SizedBox(height: 4),
          // Volume progress bar
          LinearProgressIndicator(
            value: volumePercent,
            minHeight: 3,
            backgroundColor: theme.colorScheme.surfaceContainerHighest,
            valueColor: AlwaysStoppedAnimation<Color>(
              order.isBuyOrder ? Colors.green : Colors.orange,
            ),
          ),
          const SizedBox(height: 4),
          Row(
            children: [
              Text(
                'Range: ${order.range}',
                style: theme.textTheme.labelSmall?.copyWith(
                  color: theme.colorScheme.onSurfaceVariant,
                  fontSize: 10,
                ),
              ),
              const Spacer(),
              Text(
                'Issued: ${_fmtDate(order.issued)}',
                style: theme.textTheme.labelSmall?.copyWith(
                  color: theme.colorScheme.onSurfaceVariant,
                  fontSize: 10,
                ),
              ),
            ],
          ),
        ],
      ),
      trailing: IconButton(
        icon: const Icon(Icons.copy, size: 16),
        tooltip: 'Copy price to clipboard',
        onPressed: () {
          Clipboard.setData(ClipboardData(text: order.price.toStringAsFixed(2)));
          ScaffoldMessenger.of(context).showSnackBar(
            SnackBar(
              content: Text('Copied ${_fmtIsk(order.price)} ISK'),
              duration: const Duration(seconds: 1),
            ),
          );
        },
      ),
    );
  }
}

String _fmtIsk(double v) {
  final abs = v.abs();
  if (abs >= 1e9) return '${(v / 1e9).toStringAsFixed(2)}B';
  if (abs >= 1e6) return '${(v / 1e6).toStringAsFixed(2)}M';
  if (abs >= 1e3) return '${(v / 1e3).toStringAsFixed(2)}K';
  return v.toStringAsFixed(2);
}

String _fmtDate(DateTime d) {
  return '${d.year}-${d.month.toString().padLeft(2, '0')}-${d.day.toString().padLeft(2, '0')}';
}
