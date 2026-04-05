import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../core/esi/esi_provider.dart';
import '../../../core/sde/sde_database.dart';
import '../../../core/sde/sde_models.dart';
import '../../../core/sde/sde_provider.dart';
import '../data/market_order_repository.dart';

// ---------------------------------------------------------------------------
// Providers
// ---------------------------------------------------------------------------

final _marketOrderRepoProvider = Provider<MarketOrderRepository>((ref) {
  return MarketOrderRepository(esi: ref.watch(esiClientProvider));
});

final _selectedRegionProvider =
    NotifierProvider<_SelectedRegionNotifier, int>(_SelectedRegionNotifier.new);

class _SelectedRegionNotifier extends Notifier<int> {
  @override
  int build() => 10000002; // The Forge (Jita)
  void select(int id) => state = id;
}

final _selectedGroupProvider =
    NotifierProvider<_SelectedGroupNotifier, InvMarketGroup?>(
        _SelectedGroupNotifier.new);

class _SelectedGroupNotifier extends Notifier<InvMarketGroup?> {
  @override
  InvMarketGroup? build() => null;
  void select(InvMarketGroup? g) => state = g;
}

final _selectedTypeProvider =
    NotifierProvider<_SelectedTypeNotifier, InvType?>(
        _SelectedTypeNotifier.new);

class _SelectedTypeNotifier extends Notifier<InvType?> {
  @override
  InvType? build() => null;
  void select(InvType? t) => state = t;
}

final _marketOrdersProvider =
    FutureProvider.autoDispose<List<MarketOrder>>((ref) async {
  final type = ref.watch(_selectedTypeProvider);
  if (type == null) return [];
  final regionId = ref.watch(_selectedRegionProvider);
  return ref.watch(_marketOrderRepoProvider).fetchOrders(regionId, type.typeId);
});

final _marketGroupChildrenProvider =
    Provider<Map<int?, List<InvMarketGroup>>>((ref) {
  final db = ref.watch(sdeDatabaseProvider);
  if (db == null) return {};
  final groups = db.getMarketGroups();
  final map = <int?, List<InvMarketGroup>>{};
  for (final g in groups) {
    (map[g.parentGroupId] ??= []).add(g);
  }
  for (final list in map.values) {
    list.sort((a, b) => a.marketGroupName.compareTo(b.marketGroupName));
  }
  return map;
});

// ---------------------------------------------------------------------------
// Screen
// ---------------------------------------------------------------------------

class MarketBrowserScreen extends ConsumerWidget {
  const MarketBrowserScreen({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final sdeState = ref.watch(sdeInitProvider);
    final db = ref.watch(sdeDatabaseProvider);

    if (sdeState.isDownloading || (!sdeState.isReady && sdeState.error == null)) {
      return _SdeLoadingView(
        progress: sdeState.isDownloading ? sdeState.downloadProgress : null,
      );
    }

    if (sdeState.error != null) {
      return _SdeErrorView(
        error: sdeState.error!,
        onRetry: () => ref.read(sdeInitProvider.notifier).retry(),
      );
    }

    if (db == null) return const SizedBox.shrink();

    return const _BrowserLayout();
  }
}

// ---------------------------------------------------------------------------
// Loading / error states
// ---------------------------------------------------------------------------

class _SdeLoadingView extends StatelessWidget {
  final double? progress;
  const _SdeLoadingView({this.progress});

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    return Center(
      child: SizedBox(
        width: 320,
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            const Icon(Icons.download_rounded, size: 48),
            const SizedBox(height: 16),
            Text(
              progress == null
                  ? 'Checking EVE data…'
                  : 'Downloading EVE data…',
              style: theme.textTheme.titleMedium,
            ),
            const SizedBox(height: 16),
            LinearProgressIndicator(
              value: (progress != null && progress! > 0) ? progress : null,
            ),
            const SizedBox(height: 8),
            Text(
              (progress != null && progress! > 0)
                  ? '${(progress! * 100).toStringAsFixed(0)}%'
                  : 'Connecting…',
              style: theme.textTheme.bodySmall,
            ),
          ],
        ),
      ),
    );
  }
}

class _SdeErrorView extends StatelessWidget {
  final String error;
  final VoidCallback onRetry;
  const _SdeErrorView({required this.error, required this.onRetry});

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    return Center(
      child: SizedBox(
        width: 360,
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            Icon(Icons.error_outline, size: 48, color: theme.colorScheme.error),
            const SizedBox(height: 16),
            Text(
              'Failed to load EVE data',
              style: theme.textTheme.titleMedium,
            ),
            const SizedBox(height: 8),
            Text(
              error,
              textAlign: TextAlign.center,
              style: theme.textTheme.bodySmall
                  ?.copyWith(color: theme.colorScheme.onSurfaceVariant),
            ),
            const SizedBox(height: 16),
            FilledButton.icon(
              onPressed: onRetry,
              icon: const Icon(Icons.refresh),
              label: const Text('Retry'),
            ),
          ],
        ),
      ),
    );
  }
}

// ---------------------------------------------------------------------------
// Main browser layout
// ---------------------------------------------------------------------------

class _BrowserLayout extends ConsumerWidget {
  const _BrowserLayout();

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final db = ref.watch(sdeDatabaseProvider)!;

    return Column(
      children: [
        _RegionBar(db: db),
        const Divider(height: 1),
        Expanded(
          child: Row(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              SizedBox(width: 240, child: _GroupTreePanel()),
              const VerticalDivider(width: 1),
              SizedBox(width: 260, child: _TypeListPanel()),
              const VerticalDivider(width: 1),
              const Expanded(child: _OrderBookPanel()),
            ],
          ),
        ),
      ],
    );
  }
}

// ---------------------------------------------------------------------------
// Region bar
// ---------------------------------------------------------------------------

class _RegionBar extends ConsumerWidget {
  final SdeDatabase db;
  const _RegionBar({required this.db});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final selectedRegion = ref.watch(_selectedRegionProvider);
    final regions = db.getRegions();

    return Padding(
      padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 6),
      child: Row(
        children: [
          Text('Region:', style: Theme.of(context).textTheme.bodySmall),
          const SizedBox(width: 8),
          DropdownButton<int>(
            value: regions.any((r) => r.regionId == selectedRegion)
                ? selectedRegion
                : null,
            isDense: true,
            hint: const Text('Select region'),
            items: regions
                .map((r) => DropdownMenuItem(
                      value: r.regionId,
                      child: Text(r.regionName),
                    ))
                .toList(),
            onChanged: (id) {
              if (id != null) {
                ref.read(_selectedRegionProvider.notifier).select(id);
                ref.read(_selectedTypeProvider.notifier).select(null);
              }
            },
          ),
        ],
      ),
    );
  }
}

// ---------------------------------------------------------------------------
// Market group tree
// ---------------------------------------------------------------------------

class _GroupTreePanel extends ConsumerStatefulWidget {
  @override
  ConsumerState<_GroupTreePanel> createState() => _GroupTreePanelState();
}

class _GroupTreePanelState extends ConsumerState<_GroupTreePanel> {
  final Set<int> _expanded = {};

  List<({InvMarketGroup group, int depth})> _visibleNodes(
      Map<int?, List<InvMarketGroup>> children) {
    final result = <({InvMarketGroup group, int depth})>[];

    void add(InvMarketGroup g, int depth) {
      result.add((group: g, depth: depth));
      if (_expanded.contains(g.marketGroupId)) {
        for (final child in children[g.marketGroupId] ?? []) {
          add(child, depth + 1);
        }
      }
    }

    for (final root in children[null] ?? []) {
      add(root, 0);
    }
    return result;
  }

  @override
  Widget build(BuildContext context) {
    final children = ref.watch(_marketGroupChildrenProvider);
    final selectedGroup = ref.watch(_selectedGroupProvider);
    final theme = Theme.of(context);
    final nodes = _visibleNodes(children);

    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Padding(
          padding: const EdgeInsets.fromLTRB(12, 8, 12, 4),
          child: Text('Market Groups',
              style: theme.textTheme.labelSmall?.copyWith(
                  color: theme.colorScheme.onSurfaceVariant)),
        ),
        Expanded(
          child: ListView.builder(
            itemCount: nodes.length,
            itemExtent: 28,
            itemBuilder: (context, index) {
              final (:group, :depth) = nodes[index];
              final hasChildren =
                  (children[group.marketGroupId]?.isNotEmpty) ?? false;
              final isExpanded = _expanded.contains(group.marketGroupId);
              final isSelected = selectedGroup?.marketGroupId ==
                  group.marketGroupId;

              return InkWell(
                onTap: () {
                  if (hasChildren) {
                    setState(() {
                      if (isExpanded) {
                        _expanded.remove(group.marketGroupId);
                      } else {
                        _expanded.add(group.marketGroupId);
                      }
                    });
                  } else if (group.hasTypes) {
                    ref
                        .read(_selectedGroupProvider.notifier)
                        .select(group);
                    ref
                        .read(_selectedTypeProvider.notifier)
                        .select(null);
                  }
                },
                child: Container(
                  color: isSelected
                      ? theme.colorScheme.primaryContainer.withValues(alpha: 0.4)
                      : null,
                  padding: EdgeInsets.only(
                    left: depth * 14.0 + 8,
                    right: 8,
                  ),
                  child: Row(
                    children: [
                      SizedBox(
                        width: 16,
                        child: hasChildren
                            ? Icon(
                                isExpanded
                                    ? Icons.expand_more
                                    : Icons.chevron_right,
                                size: 14,
                                color: theme.colorScheme.onSurfaceVariant,
                              )
                            : null,
                      ),
                      const SizedBox(width: 4),
                      Expanded(
                        child: Text(
                          group.marketGroupName,
                          overflow: TextOverflow.ellipsis,
                          style: theme.textTheme.bodySmall?.copyWith(
                            color: isSelected
                                ? theme.colorScheme.primary
                                : null,
                          ),
                        ),
                      ),
                    ],
                  ),
                ),
              );
            },
          ),
        ),
      ],
    );
  }
}

// ---------------------------------------------------------------------------
// Type (item) list
// ---------------------------------------------------------------------------

class _TypeListPanel extends ConsumerWidget {
  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final group = ref.watch(_selectedGroupProvider);
    final selectedType = ref.watch(_selectedTypeProvider);
    final db = ref.watch(sdeDatabaseProvider);
    final theme = Theme.of(context);

    if (group == null || db == null) {
      return Center(
        child: Text(
          'Select a market group',
          style: theme.textTheme.bodySmall
              ?.copyWith(color: theme.colorScheme.onSurfaceVariant),
        ),
      );
    }

    final types = db.getTypesForGroup(group.marketGroupId);

    if (types.isEmpty) {
      return Center(
        child: Text(
          'No items in this group',
          style: theme.textTheme.bodySmall
              ?.copyWith(color: theme.colorScheme.onSurfaceVariant),
        ),
      );
    }

    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Padding(
          padding: const EdgeInsets.fromLTRB(12, 8, 12, 4),
          child: Text(group.marketGroupName,
              style: theme.textTheme.labelSmall?.copyWith(
                  color: theme.colorScheme.onSurfaceVariant)),
        ),
        Expanded(
          child: ListView.builder(
            itemCount: types.length,
            itemExtent: 32,
            itemBuilder: (context, i) {
              final type = types[i];
              final isSelected =
                  selectedType?.typeId == type.typeId;

              return InkWell(
                onTap: () => ref
                    .read(_selectedTypeProvider.notifier)
                    .select(type),
                child: Container(
                  color: isSelected
                      ? theme.colorScheme.primaryContainer
                          .withValues(alpha: 0.4)
                      : null,
                  padding:
                      const EdgeInsets.symmetric(horizontal: 12, vertical: 6),
                  child: Text(
                    type.typeName,
                    overflow: TextOverflow.ellipsis,
                    style: theme.textTheme.bodySmall?.copyWith(
                      color: isSelected ? theme.colorScheme.primary : null,
                    ),
                  ),
                ),
              );
            },
          ),
        ),
      ],
    );
  }
}

// ---------------------------------------------------------------------------
// Order book
// ---------------------------------------------------------------------------

class _OrderBookPanel extends ConsumerWidget {
  const _OrderBookPanel();

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final type = ref.watch(_selectedTypeProvider);
    final ordersAsync = ref.watch(_marketOrdersProvider);
    final theme = Theme.of(context);

    if (type == null) {
      return Center(
        child: Text(
          'Select an item to view orders',
          style: theme.textTheme.bodySmall
              ?.copyWith(color: theme.colorScheme.onSurfaceVariant),
        ),
      );
    }

    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Padding(
          padding: const EdgeInsets.fromLTRB(12, 8, 12, 4),
          child: Row(
            children: [
              Expanded(
                child: Text(
                  type.typeName,
                  style: theme.textTheme.titleSmall,
                  overflow: TextOverflow.ellipsis,
                ),
              ),
              if (type.volume != null)
                Text(
                  '${type.volume!.toStringAsFixed(2)} m³',
                  style: theme.textTheme.labelSmall?.copyWith(
                      color: theme.colorScheme.onSurfaceVariant),
                ),
            ],
          ),
        ),
        const Divider(height: 1),
        Expanded(
          child: ordersAsync.when(
            loading: () => const Center(child: CircularProgressIndicator()),
            error: (e, _) => Center(
              child: Text('Error: $e',
                  style: TextStyle(color: theme.colorScheme.error)),
            ),
            data: (orders) => _OrderList(orders: orders),
          ),
        ),
      ],
    );
  }
}

class _OrderList extends StatelessWidget {
  final List<MarketOrder> orders;
  const _OrderList({required this.orders});

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final sells = orders.where((o) => !o.isBuyOrder).toList();
    final buys = orders.where((o) => o.isBuyOrder).toList();

    if (orders.isEmpty) {
      return Center(
        child: Text('No orders found',
            style: theme.textTheme.bodySmall
                ?.copyWith(color: theme.colorScheme.onSurfaceVariant)),
      );
    }

    return ListView(
      children: [
        _OrderSectionHeader(label: 'Sell Orders (${sells.length})', isSell: true),
        _OrderTableHeader(),
        ...sells.take(100).map((o) => _OrderRow(order: o, isBestSell: o == sells.firstOrNull)),
        _OrderSectionHeader(label: 'Buy Orders (${buys.length})', isSell: false),
        _OrderTableHeader(),
        ...buys.take(100).map((o) => _OrderRow(order: o, isBestBuy: o == buys.firstOrNull)),
      ],
    );
  }
}

class _OrderSectionHeader extends StatelessWidget {
  final String label;
  final bool isSell;
  const _OrderSectionHeader({required this.label, required this.isSell});

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    return Container(
      color: theme.colorScheme.surfaceContainerHighest,
      padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 4),
      child: Text(
        label,
        style: theme.textTheme.labelSmall?.copyWith(
          color: isSell ? Colors.redAccent : Colors.green,
          fontWeight: FontWeight.w600,
        ),
      ),
    );
  }
}

class _OrderTableHeader extends StatelessWidget {
  @override
  Widget build(BuildContext context) {
    final style = Theme.of(context).textTheme.labelSmall?.copyWith(
          color: Theme.of(context).colorScheme.onSurfaceVariant,
        );
    return Padding(
      padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 2),
      child: Row(
        children: [
          Expanded(flex: 3, child: Text('Price', style: style)),
          Expanded(flex: 2, child: Text('Qty', style: style, textAlign: TextAlign.end)),
          Expanded(flex: 2, child: Text('Total', style: style, textAlign: TextAlign.end)),
        ],
      ),
    );
  }
}

class _OrderRow extends StatelessWidget {
  final MarketOrder order;
  final bool isBestSell;
  final bool isBestBuy;
  const _OrderRow({required this.order, this.isBestSell = false, this.isBestBuy = false});

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final isBest = isBestSell || isBestBuy;
    final priceColor = order.isBuyOrder
        ? Colors.green
        : Colors.redAccent;

    return Container(
      color: isBest
          ? (order.isBuyOrder
              ? Colors.green.withValues(alpha: 0.08)
              : Colors.red.withValues(alpha: 0.08))
          : null,
      padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 3),
      child: Row(
        children: [
          Expanded(
            flex: 3,
            child: Text(
              _formatPrice(order.price),
              style: theme.textTheme.bodySmall?.copyWith(
                color: priceColor,
                fontWeight: isBest ? FontWeight.w700 : null,
              ),
            ),
          ),
          Expanded(
            flex: 2,
            child: Text(
              _formatVol(order.volumeRemain),
              textAlign: TextAlign.end,
              style: theme.textTheme.bodySmall,
            ),
          ),
          Expanded(
            flex: 2,
            child: Text(
              _formatPrice(order.price * order.volumeRemain),
              textAlign: TextAlign.end,
              style: theme.textTheme.bodySmall?.copyWith(
                color: theme.colorScheme.onSurfaceVariant,
              ),
            ),
          ),
        ],
      ),
    );
  }

  static String _formatPrice(double v) {
    if (v >= 1e9) return '${(v / 1e9).toStringAsFixed(2)}B';
    if (v >= 1e6) return '${(v / 1e6).toStringAsFixed(2)}M';
    if (v >= 1e3) return '${(v / 1e3).toStringAsFixed(2)}K';
    return v.toStringAsFixed(2);
  }

  static String _formatVol(int v) {
    if (v >= 1e6) return '${(v / 1e6).toStringAsFixed(1)}M';
    if (v >= 1e3) return '${(v / 1e3).toStringAsFixed(1)}K';
    return v.toString();
  }
}
