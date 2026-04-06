import 'dart:async';
import 'dart:math';

import 'package:drift/drift.dart' as drift;
import 'package:fl_chart/fl_chart.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../core/database/app_database.dart';
import '../../../core/database/database_provider.dart';
import '../../../core/esi/esi_provider.dart';
import '../../../core/sde/sde_models.dart';
import '../../../core/sde/sde_provider.dart';
import '../../../core/sde/sde_database.dart';
import 'price_alerts_panel.dart';
import '../../../core/alerts/price_alert_service.dart';
import '../../market_analysis/data/market_history_entry.dart';
import '../../market_analysis/data/market_history_repository.dart';
import '../../market_analysis/domain/market_indicators.dart';
import '../data/market_order_repository.dart';

// ---------------------------------------------------------------------------
// Providers
// ---------------------------------------------------------------------------

final _marketOrderRepoProvider = Provider<MarketOrderRepository>((ref) {
  final db = ref.watch(sdeDatabaseProvider);
  if (db == null) {
    throw StateError('SDE database not yet available');
  }
  return MarketOrderRepository(
    esi: ref.watch(esiClientProvider),
    sde: db,
  );
});

final _selectedRegionProvider =
    NotifierProvider<_SelectedRegionNotifier, int>(_SelectedRegionNotifier.new);

class _SelectedRegionNotifier extends Notifier<int> {
  @override
  int build() => 10000002; // The Forge (Jita)
  void select(int id) => state = id;
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

// Auto-check alerts when orders are fetched
final _alertCheckOnOrdersProvider = FutureProvider.autoDispose<void>((ref) async {
  final orders = ref.watch(_marketOrdersProvider);
  orders.whenData((ordersList) {
    if (ordersList.isEmpty) return;
    final db = ref.watch(databaseProvider);
    final esi = ref.watch(esiClientProvider);
    final service = PriceAlertService(db: db, esi: esi);
    service.checkAlerts().then((triggered) {
      for (final msg in triggered) {
        service.showNotification(msg);
      }
    });
  });
});

// Provider that returns the next cache expiry time for market orders
final _nextMarketCacheExpiryProvider = FutureProvider.autoDispose<DateTime?>((ref) async {
  final type = ref.watch(_selectedTypeProvider);
  final regionId = ref.watch(_selectedRegionProvider);
  if (type == null) return null;

  final db = ref.watch(databaseProvider);
  final effectiveRegion = fixedMarketRegions[type.typeId] ?? regionId;
  final urlPattern = '/markets/$effectiveRegion/orders/';

  final entries = await (db.select(db.esiCache)
        ..where((t) => t.url.contains(urlPattern))
        ..orderBy([(t) => drift.OrderingTerm(expression: t.expiresAt)]))
      .get();

  if (entries.isEmpty) return null;
  // Return the earliest expiry
  return entries.first.expiresAt;
});

final _marketHistoryRepoProvider = Provider<MarketHistoryRepository>((ref) {
  return MarketHistoryRepository(esi: ref.watch(esiClientProvider));
});

final _marketHistoryProvider =
    FutureProvider.autoDispose<List<MarketHistoryEntry>>((ref) async {
  final type = ref.watch(_selectedTypeProvider);
  if (type == null) return [];
  final regionId = ref.watch(_selectedRegionProvider);
  return ref
      .watch(_marketHistoryRepoProvider)
      .fetchHistory(regionId, type.typeId);
});

final _chartViewModeProvider =
    NotifierProvider<_ChartViewModeNotifier, ChartViewMode>(
        _ChartViewModeNotifier.new);

class _ChartViewModeNotifier extends Notifier<ChartViewMode> {
  @override
  ChartViewMode build() => ChartViewMode.candlestick;
  void setMode(ChartViewMode mode) => state = mode;
}

enum ChartViewMode { candlestick, line }

final _chartPeriodProvider =
    NotifierProvider<_ChartPeriodNotifier, int>(_ChartPeriodNotifier.new);

class _ChartPeriodNotifier extends Notifier<int> {
  @override
  int build() => 90; // days
  void setDays(int days) => state = days;
}

final _searchQueryProvider =
    NotifierProvider<_SearchQueryNotifier, String>(_SearchQueryNotifier.new);

class _SearchQueryNotifier extends Notifier<String> {
  Timer? _debounce;
  @override
  String build() => '';
  void setQuery(String query) {
    _debounce?.cancel();
    _debounce = Timer(const Duration(milliseconds: 300), () {
      state = query;
    });
  }
  void clear() {
    _debounce?.cancel();
    state = '';
  }
}

// Provider that uses SdeDatabase.searchTypes (already optimized)
final _searchResultsProvider = FutureProvider.autoDispose<List<InvType>>(
  (ref) async {
    final query = ref.watch(_searchQueryProvider);
    if (query.isEmpty) return [];
    final db = ref.watch(sdeDatabaseProvider);
    if (db == null) return [];
    // Use the built-in DB search (limit 100)
    return db.searchTypes(query, limit: 100);
  },
);

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

class _BrowserLayout extends ConsumerStatefulWidget {
  const _BrowserLayout();

  @override
  ConsumerState<_BrowserLayout> createState() => _BrowserLayoutState();
}

class _BrowserLayoutState extends ConsumerState<_BrowserLayout>
    with SingleTickerProviderStateMixin {
  late final TabController _tabController;
  bool _showAlertsPanel = false;
  Timer? _alertCheckTimer;

  @override
  void initState() {
    super.initState();
    _tabController = TabController(length: 2, vsync: this);
    // Schedule alert check based on ESI cache expiry
    _scheduleAlertCheck();
  }

  @override
  void dispose() {
    _tabController.dispose();
    _alertCheckTimer?.cancel();
    super.dispose();
  }

  /// Schedules the next alert check based on ESI cache expiry time.
  /// Falls back to 5 minutes if no cache data available.
  void _scheduleAlertCheck() {
    _alertCheckTimer?.cancel();
    // Use a small delay to ensure providers are ready
    Future.delayed(const Duration(milliseconds: 500), () {
      if (!mounted) return;
      final expiry = ref.read(_nextMarketCacheExpiryProvider);
      expiry.whenData((expiresAt) {
        if (!mounted) return;
        if (expiresAt != null) {
          final now = DateTime.now().toUtc();
          final delay = expiresAt.difference(now);
          if (delay.isNegative) {
            // Already expired — check now and reschedule
            _checkAllAlerts();
            _alertCheckTimer = Timer(const Duration(minutes: 5), () {
              _scheduleAlertCheck();
            });
          } else {
            // Schedule check at expiry time
            _alertCheckTimer = Timer(delay, () {
              _checkAllAlerts();
              _scheduleAlertCheck(); // Reschedule for next expiry
            });
          }
        } else {
          // No cache data yet — fallback to 5 minutes
          _alertCheckTimer = Timer(const Duration(minutes: 5), () {
            _checkAllAlerts();
            _scheduleAlertCheck();
          });
        }
      });
    });
  }

  void _checkAllAlerts() {
    final db = ref.read(databaseProvider);
    final esi = ref.read(esiClientProvider);
    final service = PriceAlertService(db: db, esi: esi);
    service.checkAlerts().then((triggered) {
      for (final msg in triggered) {
        service.showNotification(msg);
      }
      if (mounted && triggered.isNotEmpty) {
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(
            content: Text('${triggered.length} price alert(s) triggered!'),
            duration: const Duration(seconds: 5),
          ),
        );
      }
    });
  }

  @override
  Widget build(BuildContext context) {
    final db = ref.watch(sdeDatabaseProvider)!;

    return Column(
      children: [
        _RegionBar(db: db),
        const Divider(height: 1),
        Expanded(
          child: Row(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              SizedBox(width: 320, child: _GroupTreePanel()),
              const VerticalDivider(width: 1),
              Expanded(
                child: Column(
                  children: [
                    TabBar(
                      controller: _tabController,
                      tabs: const [
                        Tab(icon: Icon(Icons.list, size: 18), text: 'Orders'),
                        Tab(
                            icon: Icon(Icons.candlestick_chart, size: 18),
                            text: 'Price History'),
                      ],
                      labelPadding: EdgeInsets.zero,
                    ),
                    Align(
                      alignment: Alignment.centerRight,
                      child: IconButton(
                        icon: const Icon(Icons.notifications_active, size: 18),
                        tooltip: 'Price Alerts',
                        onPressed: () =>
                            setState(() => _showAlertsPanel = !_showAlertsPanel),
                      ),
                    ),
                    Expanded(
                      child: TabBarView(
                        controller: _tabController,
                        children: const [
                          _OrderBookPanel(),
                          _PriceHistoryChart(),
                        ],
                      ),
                    ),
                  ],
                ),
              ),
              // Alerts panel toggle
              AnimatedContainer(
                duration: const Duration(milliseconds: 200),
                width: _showAlertsPanel ? 280 : 0,
                child: _showAlertsPanel
                    ? Column(
                        children: [
                          Row(
                            children: [
                              IconButton(
                                icon: const Icon(Icons.close, size: 18),
                                tooltip: 'Close alerts panel',
                                onPressed: () =>
                                    setState(() => _showAlertsPanel = false),
                              ),
                              const Expanded(child: SizedBox.shrink()),
                            ],
                          ),
                          const Expanded(child: PriceAlertsPanel()),
                        ],
                      )
                    : null,
              ),
              if (_showAlertsPanel) const VerticalDivider(width: 1),
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
// Market group tree with types and search
// ---------------------------------------------------------------------------

// Tree node types (must be top-level, not inside a class)
sealed class _TreeNode {}
class _GroupNode extends _TreeNode {
  final InvMarketGroup group;
  final int depth;
  _GroupNode(this.group, this.depth);
}
class _TypeNode extends _TreeNode {
  final InvType type;
  final int depth;
  _TypeNode(this.type, this.depth);
}

class _GroupTreePanel extends ConsumerStatefulWidget {
  @override
  ConsumerState<_GroupTreePanel> createState() => _GroupTreePanelState();
}

class _GroupTreePanelState extends ConsumerState<_GroupTreePanel> {
  final Set<int> _expanded = {};
  final TextEditingController _searchController = TextEditingController();

  @override
  void dispose() {
    _searchController.dispose();
    super.dispose();
  }

  // Helper to get all groups as a map
  Map<int?, List<InvMarketGroup>> _allGroupsMap(SdeDatabase db) {
    final groups = db.getMarketGroups();
    final map = <int?, List<InvMarketGroup>>{};
    for (final g in groups) {
      (map[g.parentGroupId] ??= []).add(g);
    }
    for (final list in map.values) {
      list.sort((a, b) => a.marketGroupName.compareTo(b.marketGroupName));
    }
    return map;
  }

  @override
  Widget build(BuildContext context) {
    final db = ref.watch(sdeDatabaseProvider);
    final selectedType = ref.watch(_selectedTypeProvider);
    final searchQuery = ref.watch(_searchQueryProvider);
    final theme = Theme.of(context);

    if (db == null) {
      return const Center(child: CircularProgressIndicator());
    }

    final children = _allGroupsMap(db);

    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Padding(
          padding: const EdgeInsets.fromLTRB(12, 8, 12, 4),
          child: Text('Market Groups',
              style: theme.textTheme.labelSmall?.copyWith(
                  color: theme.colorScheme.onSurfaceVariant)),
        ),
        // Search field
        Padding(
          padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 4),
          child: TextField(
            controller: _searchController,
            decoration: InputDecoration(
              hintText: 'Search items...',
              prefixIcon: const Icon(Icons.search, size: 20),
              suffixIcon: searchQuery.isNotEmpty
                  ? IconButton(
                      icon: const Icon(Icons.clear, size: 20),
                      onPressed: () {
                        _searchController.clear();
                        ref.read(_searchQueryProvider.notifier).clear();
                      },
                    )
                  : null,
              contentPadding:
                  const EdgeInsets.symmetric(horizontal: 12, vertical: 8),
              isDense: true,
              border: OutlineInputBorder(
                borderRadius: BorderRadius.circular(8),
              ),
            ),
            onChanged: (value) {
              ref.read(_searchQueryProvider.notifier).setQuery(value);
            },
          ),
        ),
        if (searchQuery.isNotEmpty)
          // Search results view
          Expanded(
            child: ref.watch(_searchResultsProvider).when(
              loading: () => const Center(child: CircularProgressIndicator()),
              error: (e, _) => Center(
                child: Text('Search error: $e',
                    style: TextStyle(color: theme.colorScheme.error)),
              ),
              data: (results) {
                if (results.isEmpty) {
                  return Padding(
                    padding: const EdgeInsets.all(12),
                    child: Text(
                      'No results for "$searchQuery"',
                      style: theme.textTheme.bodySmall
                          ?.copyWith(color: theme.colorScheme.onSurfaceVariant),
                    ),
                  );
                }
                return ListView.builder(
                  itemCount: results.length,
                  itemExtent: 32,
                  itemBuilder: (context, index) {
                    final type = results[index];
                    final isSelected = selectedType?.typeId == type.typeId;

                    return InkWell(
                      onTap: () => ref
                          .read(_selectedTypeProvider.notifier)
                          .select(type),
                      child: Container(
                        color: isSelected
                            ? theme.colorScheme.primaryContainer
                                .withValues(alpha: 0.4)
                            : null,
                        padding: const EdgeInsets.symmetric(
                            horizontal: 12, vertical: 4),
                        child: Text(
                          type.typeName,
                          overflow: TextOverflow.ellipsis,
                          style: theme.textTheme.bodySmall?.copyWith(
                            color: isSelected ? theme.colorScheme.primary : null,
                            fontWeight: isSelected ? FontWeight.w600 : null,
                          ),
                        ),
                      ),
                    );
                  },
                );
              },
            ),
          )
        else
          // Tree view
          Expanded(
            child: ListView.builder(
              itemCount: _countTreeNodes(children),
              itemBuilder: (context, index) {
                final node = _getTreeNode(children, db, index);
                return switch (node) {
                  _GroupNode(:final group, :final depth) => _buildGroupRow(
                      group, depth, children, selectedType, theme, db),
                  _TypeNode(:final type, :final depth) =>
                    _buildTypeRow(type, depth, selectedType, theme),
                };
              },
            ),
          ),
      ],
    );
  }

  // Cached tree nodes to avoid rebuilding on every frame
  List<_TreeNode>? _cachedTreeNodes;
  Map<int?, List<InvMarketGroup>>? _cachedChildrenMap;

  int _countTreeNodes(Map<int?, List<InvMarketGroup>> children) {
    if (_cachedChildrenMap != children) {
      _cachedChildrenMap = children;
      _cachedTreeNodes = _buildTreeNodes(children, ref.watch(sdeDatabaseProvider)!);
    }
    return _cachedTreeNodes?.length ?? 0;
  }

  _TreeNode _getTreeNode(
      Map<int?, List<InvMarketGroup>> children, SdeDatabase db, int index) {
    if (_cachedChildrenMap != children) {
      _cachedChildrenMap = children;
      _cachedTreeNodes = _buildTreeNodes(children, db);
    }
    return _cachedTreeNodes![index];
  }

  List<_TreeNode> _buildTreeNodes(
      Map<int?, List<InvMarketGroup>> children, SdeDatabase db) {
    final result = <_TreeNode>[];
    void add(InvMarketGroup g, int depth) {
      result.add(_GroupNode(g, depth));
      if (_expanded.contains(g.marketGroupId)) {
        final hasVisibleChildren =
            (children[g.marketGroupId]?.isNotEmpty) ?? false;
        if (hasVisibleChildren) {
          for (final child in children[g.marketGroupId] ?? []) {
            add(child, depth + 1);
          }
        }
        if (g.hasTypes) {
          final types = db.getTypesForGroup(g.marketGroupId);
          for (final t in types) {
            result.add(_TypeNode(t, depth + 1));
          }
        }
      }
    }
    for (final root in children[null] ?? []) {
      add(root, 0);
    }
    return result;
  }

  Widget _buildGroupRow(
    InvMarketGroup group,
    int depth,
    Map<int?, List<InvMarketGroup>> children,
    InvType? selectedType,
    ThemeData theme,
    SdeDatabase db,
  ) {
    final hasChildren = (children[group.marketGroupId]?.isNotEmpty) ?? false;
    final isExpanded = _expanded.contains(group.marketGroupId);

    return InkWell(
      onTap: () {
        setState(() {
          if (isExpanded) {
            _expanded.remove(group.marketGroupId);
          } else {
            _expanded.add(group.marketGroupId);
          }
        });
      },
      child: Container(
        padding: EdgeInsets.only(
          left: depth * 14.0 + 8,
          right: 8,
        ),
        child: Row(
          children: [
            SizedBox(
              width: 16,
              child: hasChildren || group.hasTypes
                  ? Icon(
                      isExpanded ? Icons.expand_more : Icons.chevron_right,
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
                style: theme.textTheme.bodySmall,
              ),
            ),
            if (group.hasTypes)
              Text(
                '(${db.getTypesForGroup(group.marketGroupId).length})',
                style: theme.textTheme.labelSmall?.copyWith(
                  color: theme.colorScheme.onSurfaceVariant,
                  fontSize: 9,
                ),
              ),
          ],
        ),
      ),
    );
  }

  Widget _buildTypeRow(
    InvType type,
    int depth,
    InvType? selectedType,
    ThemeData theme,
  ) {
    final isSelected = selectedType?.typeId == type.typeId;

    return InkWell(
      onTap: () => ref.read(_selectedTypeProvider.notifier).select(type),
      child: Container(
        color: isSelected
            ? theme.colorScheme.primaryContainer.withValues(alpha: 0.4)
            : null,
        padding: EdgeInsets.only(
          left: depth * 14.0 + 22,
          right: 8,
        ),
        child: Text(
          type.typeName,
          overflow: TextOverflow.ellipsis,
          style: theme.textTheme.bodySmall?.copyWith(
            color: isSelected ? theme.colorScheme.primary : null,
          ),
        ),
      ),
    );
  }
}

// ---------------------------------------------------------------------------
// Set price alert dialog
// ---------------------------------------------------------------------------

class _SetPriceAlertDialog extends ConsumerStatefulWidget {
  final MarketOrder order;
  const _SetPriceAlertDialog({required this.order});

  @override
  ConsumerState<_SetPriceAlertDialog> createState() => _SetPriceAlertDialogState();
}

class _SetPriceAlertDialogState extends ConsumerState<_SetPriceAlertDialog> {
  final _controller = TextEditingController();
  String _condition = 'below';

  @override
  void dispose() {
    _controller.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    _controller.text = widget.order.price.toStringAsFixed(2);

    return AlertDialog(
      title: const Text('Set Price Alert'),
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
              final regionId = ref.read(_selectedRegionProvider);
              db.into(db.priceAlerts).insert(
                    PriceAlertsCompanion.insert(
                      typeId: widget.order.typeId,
                      regionId: regionId,
                      targetPrice: price,
                      condition: _condition,
                      createdAt: DateTime.now(),
                    ),
                  );
              Navigator.of(context).pop();
              ScaffoldMessenger.of(context).showSnackBar(
                SnackBar(
                  content: Text(
                    'Alert set: Type #${widget.order.typeId} $_condition $price ISK',
                  ),
                ),
              );
            }
          },
          child: const Text('Set Alert'),
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
    // Auto-check alerts when orders are fetched
    ref.watch(_alertCheckOnOrdersProvider);
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
            data: (orders) => _SplitOrderList(orders: orders),
          ),
        ),
      ],
    );
  }
}

class _SplitOrderList extends StatelessWidget {
  final List<MarketOrder> orders;
  const _SplitOrderList({required this.orders});

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

    return Column(
      children: [
        // Sell orders section (top half)
        Expanded(
          child: Column(
            children: [
              _OrderSectionHeader(
                  label: 'Sell Orders (${sells.length})', isSell: true),
              _OrderTableHeader(),
              Expanded(
                child: ListView(
                  children: sells
                      .take(100)
                      .map((o) =>
                          _OrderRow(order: o, isBestSell: o == sells.firstOrNull))
                      .toList(),
                ),
              ),
            ],
          ),
        ),
        const Divider(height: 2),
        // Buy orders section (bottom half)
        Expanded(
          child: Column(
            children: [
              _OrderSectionHeader(
                  label: 'Buy Orders (${buys.length})', isSell: false),
              _OrderTableHeader(),
              Expanded(
                child: ListView(
                  children: buys
                      .take(100)
                      .map((o) =>
                          _OrderRow(order: o, isBestBuy: o == buys.firstOrNull))
                      .toList(),
                ),
              ),
            ],
          ),
        ),
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
          Expanded(flex: 4, child: Text('Station', style: style)),
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

    return GestureDetector(
      onLongPress: () => _showAlertDialog(context, order),
      child: Container(
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
              _formatFullPrice(order.price),
              style: theme.textTheme.bodySmall?.copyWith(
                color: priceColor,
                fontWeight: isBest ? FontWeight.w700 : null,
              ),
            ),
          ),
          Expanded(
            flex: 4,
            child: Text(
              order.locationName ?? '${order.locationId}',
              overflow: TextOverflow.ellipsis,
              style: theme.textTheme.bodySmall?.copyWith(
                color: theme.colorScheme.onSurfaceVariant,
                fontSize: 10,
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
              _formatFullPrice(order.price * order.volumeRemain),
              textAlign: TextAlign.end,
              style: theme.textTheme.bodySmall?.copyWith(
                color: theme.colorScheme.onSurfaceVariant,
              ),
            ),
          ),
        ],
      ),
    ),
    );
  }

  void _showAlertDialog(BuildContext context, MarketOrder order) {
    showDialog(
      context: context,
      builder: (ctx) => _SetPriceAlertDialog(order: order),
    );
  }

  static String _formatFullPrice(double v) {
    final formatted = v.toStringAsFixed(2);
    final parts = formatted.split('.');
    final intPart = parts[0];
    final decPart = parts[1];
    // Add spaces as thousand separators
    final buffer = StringBuffer();
    for (int i = 0; i < intPart.length; i++) {
      if (i > 0 && (intPart.length - i) % 3 == 0) {
        buffer.write(' ');
      }
      buffer.write(intPart[i]);
    }
    return '${buffer.toString()}.$decPart';
  }

  static String _formatVol(int v) {
    if (v >= 1e6) return '${(v / 1e6).toStringAsFixed(1)}M';
    if (v >= 1e3) return '${(v / 1e3).toStringAsFixed(1)}K';
    return v.toString();
  }
}

// ---------------------------------------------------------------------------
// Price History Chart
// ---------------------------------------------------------------------------

class _PriceHistoryChart extends ConsumerWidget {
  const _PriceHistoryChart();

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final type = ref.watch(_selectedTypeProvider);
    final historyAsync = ref.watch(_marketHistoryProvider);
    final chartMode = ref.watch(_chartViewModeProvider);
    final periodDays = ref.watch(_chartPeriodProvider);
    final theme = Theme.of(context);

    if (type == null) {
      return Center(
        child: Text(
          'Select an item to view price history',
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
                  '${type.typeName} - Price History',
                  style: theme.textTheme.titleSmall,
                  overflow: TextOverflow.ellipsis,
                ),
              ),
              // Period selector
              _PeriodSelector(periodDays: periodDays),
              const SizedBox(width: 8),
              // Chart mode toggle
              SegmentedButton<ChartViewMode>(
                segments: const [
                  ButtonSegment(
                    value: ChartViewMode.candlestick,
                    icon: Icon(Icons.candlestick_chart, size: 18),
                    label: Text('Candles'),
                  ),
                  ButtonSegment(
                    value: ChartViewMode.line,
                    icon: Icon(Icons.show_chart, size: 18),
                    label: Text('Line'),
                  ),
                ],
                selected: {chartMode},
                onSelectionChanged: (modes) {
                  if (modes.isNotEmpty) {
                    ref
                        .read(_chartViewModeProvider.notifier)
                        .setMode(modes.first);
                  }
                },
              ),
            ],
          ),
        ),
        const Divider(height: 1),
        Expanded(
          child: historyAsync.when(
            loading: () => const Center(child: CircularProgressIndicator()),
            error: (e, _) => Center(
              child: Text('Error: $e',
                  style: TextStyle(color: theme.colorScheme.error)),
            ),
            data: (history) {
              if (history.isEmpty) {
                return Center(
                  child: Text('No price history available',
                      style: theme.textTheme.bodySmall?.copyWith(
                          color: theme.colorScheme.onSurfaceVariant)),
                );
              }

              // Filter by period
              final now = DateTime.now();
              final cutoff = now.subtract(Duration(days: periodDays));
              final filteredHistory = history
                  .where((e) => e.date.isAfter(cutoff))
                  .toList();

              if (filteredHistory.isEmpty) {
                return Center(
                  child: Text('No data for selected period',
                      style: theme.textTheme.bodySmall?.copyWith(
                          color: theme.colorScheme.onSurfaceVariant)),
                );
              }

              return chartMode == ChartViewMode.candlestick
                  ? _CandlestickChartWidget(entries: filteredHistory)
                  : _LineChartWidget(entries: filteredHistory);
            },
          ),
        ),
      ],
    );
  }
}

class _PeriodSelector extends ConsumerWidget {
  final int periodDays;
  const _PeriodSelector({required this.periodDays});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final theme = Theme.of(context);
    return Row(
      mainAxisSize: MainAxisSize.min,
      children: [
        Text('Period:',
            style: theme.textTheme.labelSmall
                ?.copyWith(color: theme.colorScheme.onSurfaceVariant)),
        const SizedBox(width: 4),
        Wrap(
          spacing: 4,
          children: [30, 90, 180, 365].map((days) {
            final isSelected = periodDays == days;
            return ChoiceChip(
              label: Text('$days'),
              selected: isSelected,
              onSelected: (selected) {
                if (selected) {
                  ref.read(_chartPeriodProvider.notifier).setDays(days);
                }
              },
              visualDensity: VisualDensity.compact,
            );
          }).toList(),
        ),
      ],
    );
  }
}

class _CandlestickChartWidget extends ConsumerStatefulWidget {
  final List<MarketHistoryEntry> entries;
  const _CandlestickChartWidget({required this.entries});

  @override
  ConsumerState<_CandlestickChartWidget> createState() =>
      _CandlestickChartWidgetState();
}

class _CandlestickChartWidgetState extends ConsumerState<_CandlestickChartWidget> {
  bool _showSma = true;
  bool _showVolume = true;
  bool _showMacd = false;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final entries = widget.entries;

    if (entries.isEmpty) {
      return Center(
        child: Text('No data',
            style: theme.textTheme.bodySmall
                ?.copyWith(color: theme.colorScheme.onSurfaceVariant)),
      );
    }

    // Compute indicators
    final averages = entries.map((e) => e.average).toList();
    final sma20 = MarketIndicators.computeSma(averages, period: 20);
    final macd = MarketIndicators.computeMacd(averages);

    // Find price range (including SMA if shown)
    double minPrice = double.infinity;
    double maxPrice = double.negativeInfinity;
    for (final entry in entries) {
      if (entry.lowest < minPrice) minPrice = entry.lowest;
      if (entry.highest > maxPrice) maxPrice = entry.highest;
    }
    if (_showSma) {
      for (final v in sma20) {
        if (v != null) {
          if (v < minPrice) minPrice = v;
          if (v > maxPrice) maxPrice = v;
        }
      }
    }

    final pricePadding = (maxPrice - minPrice) * 0.05;
    minPrice -= pricePadding;
    maxPrice += pricePadding;

    // Volume range
    double maxVolume = 0;
    for (final entry in entries) {
      if (entry.volume > maxVolume) maxVolume = entry.volume.toDouble();
    }
    final volumePadding = maxVolume * 0.1;
    maxVolume += volumePadding;

    // MACD range
    double macdMax = 0;
    double macdMin = 0;
    if (_showMacd) {
      for (final v in [...macd.macd, ...macd.signal, ...macd.histogram]) {
        if (v != null) {
          if (v > macdMax) macdMax = v;
          if (v < macdMin) macdMin = v;
        }
      }
      final macdPadding = (macdMax - macdMin) * 0.1;
      macdMax += macdPadding;
      macdMin -= macdPadding;
    }

    // Build candle groups — separate wicks and bodies for proper centering
    final wickGroups = <BarChartGroupData>[];
    final bodyGroups = <BarChartGroupData>[];
    final volumeGroups = <BarChartGroupData>[];
    final smaSpots = <FlSpot>[];

    for (int i = 0; i < entries.length; i++) {
      final entry = entries[i];
      final prevAvg = i > 0 ? entries[i - 1].average : entry.average;
      final isBullish = entry.average >= prevAvg;
      final x = i.toDouble();

      // Candle: body from open to close, wick from low to high
      final bodyTop = entry.average >= prevAvg ? entry.average : prevAvg;
      final bodyBottom = entry.average >= prevAvg ? prevAvg : entry.average;

      // Wick group (thin bar)
      wickGroups.add(
        BarChartGroupData(
          x: i,
          barRods: [
            BarChartRodData(
              toY: entry.highest,
              fromY: entry.lowest,
              color: isBullish ? Colors.green : Colors.red,
              width: 2,
            ),
          ],
        ),
      );

      // Body group (wider bar)
      bodyGroups.add(
        BarChartGroupData(
          x: i,
          barRods: [
            BarChartRodData(
              toY: bodyTop,
              fromY: bodyBottom,
              color: isBullish ? Colors.green : Colors.red,
              width: 10,
              borderRadius: const BorderRadius.all(Radius.circular(2)),
            ),
          ],
        ),
      );

      // Volume bars
      if (_showVolume) {
        volumeGroups.add(
          BarChartGroupData(
            x: i,
            barRods: [
              BarChartRodData(
                toY: entry.volume.toDouble(),
                fromY: 0,
                color: isBullish
                    ? Colors.green.withValues(alpha: 0.5)
                    : Colors.red.withValues(alpha: 0.5),
                width: 10,
                borderRadius: const BorderRadius.vertical(top: Radius.circular(2)),
              ),
            ],
          ),
        );
      }

      // SMA spots
      if (_showSma && sma20[i] != null) {
        smaSpots.add(FlSpot(x, sma20[i]!));
      }
    }

    return Column(
      children: [
        // Indicator controls
        Padding(
          padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 4),
          child: Wrap(
            spacing: 8,
            children: [
              FilterChip(
                label: const Text('SMA(20)'),
                selected: _showSma,
                onSelected: (v) => setState(() => _showSma = v),
                selectedColor: Colors.blue.withValues(alpha: 0.2),
              ),
              FilterChip(
                label: const Text('Volume'),
                selected: _showVolume,
                onSelected: (v) => setState(() => _showVolume = v),
                selectedColor: Colors.blue.withValues(alpha: 0.2),
              ),
              FilterChip(
                label: const Text('MACD'),
                selected: _showMacd,
                onSelected: (v) => setState(() => _showMacd = v),
                selectedColor: Colors.blue.withValues(alpha: 0.2),
              ),
            ],
          ),
        ),
        const Divider(height: 1),
        // Main chart area
        Expanded(
          flex: _showMacd ? 3 : (_showVolume ? 4 : 5),
          child: InteractiveViewer(
            panEnabled: true,
            scaleEnabled: true,
            minScale: 0.5,
            maxScale: 10,
            boundaryMargin: const EdgeInsets.all(20),
            child: Stack(
            children: [
              // Wick chart (background layer - thin bars)
              BarChart(
                BarChartData(
                  barGroups: wickGroups,
                  borderData: FlBorderData(
                    show: true,
                    border: Border.all(
                      color: theme.colorScheme.onSurfaceVariant.withValues(alpha: 0.2),
                      width: 1,
                    ),
                  ),
                  gridData: FlGridData(
                    show: true,
                    drawVerticalLine: false,
                    drawHorizontalLine: true,
                    horizontalInterval: _calculateNiceInterval(maxPrice - minPrice),
                    getDrawingHorizontalLine: (value) => FlLine(
                      color: theme.colorScheme.onSurfaceVariant.withValues(alpha: 0.1),
                      strokeWidth: 1,
                    ),
                  ),
                  titlesData: FlTitlesData(
                    show: true,
                    rightTitles: const AxisTitles(
                      sideTitles: SideTitles(showTitles: false),
                    ),
                    topTitles: const AxisTitles(
                      sideTitles: SideTitles(showTitles: false),
                    ),
                    bottomTitles: AxisTitles(
                      sideTitles: SideTitles(
                        showTitles: true,
                        reservedSize: 30,
                        interval: _calculateBottomInterval(entries.length),
                        getTitlesWidget: (value, meta) {
                          final index = value.toInt();
                          final interval = _calculateBottomInterval(entries.length);
                          if (index % interval != 0) return const SizedBox.shrink();
                          if (index < 0 || index >= entries.length) {
                            return const SizedBox.shrink();
                          }
                          final date = entries[index].date;
                          return Padding(
                            padding: const EdgeInsets.only(top: 8),
                            child: Text(
                              '${date.month}/${date.day}',
                              style: theme.textTheme.labelSmall?.copyWith(
                                color: theme.colorScheme.onSurfaceVariant,
                                fontSize: 10,
                              ),
                            ),
                          );
                        },
                      ),
                    ),
                    leftTitles: AxisTitles(
                      sideTitles: SideTitles(
                        showTitles: true,
                        reservedSize: 60,
                        interval: _calculateNiceInterval(maxPrice - minPrice),
                        getTitlesWidget: (value, meta) {
                          return Padding(
                            padding: const EdgeInsets.only(right: 8),
                            child: Text(
                              _formatPriceAxis(value),
                              style: theme.textTheme.labelSmall?.copyWith(
                                color: theme.colorScheme.onSurfaceVariant,
                                fontSize: 10,
                              ),
                            ),
                          );
                        },
                      ),
                    ),
                  ),
                  barTouchData: BarTouchData(
                    enabled: true,
                    touchTooltipData: BarTouchTooltipData(
                      getTooltipColor: (_) => theme.colorScheme.surfaceContainerHighest,
                      getTooltipItem: (group, groupIndex, rod, rodIndex) {
                        if (groupIndex < 0 || groupIndex >= entries.length) {
                          return null;
                        }
                        final entry = entries[groupIndex];
                        return BarTooltipItem(
                          '${entry.date.month}/${entry.date.day}\n',
                          TextStyle(
                            color: theme.colorScheme.onSurface,
                            fontWeight: FontWeight.bold,
                          ),
                          children: [
                            TextSpan(
                              text:
                                  'H: ${_formatPrice(entry.highest)}\nL: ${_formatPrice(entry.lowest)}\nAvg: ${_formatPrice(entry.average)}\nVol: ${_formatVolume(entry.volume)}',
                              style: theme.textTheme.labelSmall,
                            ),
                          ],
                        );
                      },
                    ),
                  ),
                  minY: minPrice,
                  maxY: maxPrice,
                  alignment: BarChartAlignment.spaceAround,
                ),
              ),
              // Body chart (foreground layer - wider bars, centered on wicks)
              BarChart(
                BarChartData(
                  barGroups: bodyGroups,
                  borderData: FlBorderData(show: false),
                  gridData: const FlGridData(show: false),
                  titlesData: const FlTitlesData(show: false),
                  barTouchData: BarTouchData(enabled: false),
                  minY: minPrice,
                  maxY: maxPrice,
                  alignment: BarChartAlignment.spaceAround,
                ),
              ),
              // SMA overlay
              if (_showSma && smaSpots.isNotEmpty)
                IgnorePointer(
                  child: LineChart(
                    LineChartData(
                      lineBarsData: [
                        LineChartBarData(
                          spots: smaSpots,
                          isCurved: false,
                          color: Colors.orange,
                          barWidth: 1.5,
                          dotData: const FlDotData(show: false),
                          belowBarData: BarAreaData(show: false),
                        ),
                      ],
                      borderData: FlBorderData(show: false),
                      gridData: const FlGridData(show: false),
                      titlesData: const FlTitlesData(show: false),
                      lineTouchData: LineTouchData(enabled: false),
                      minY: minPrice,
                      maxY: maxPrice,
                      minX: 0,
                      maxX: (entries.length - 1).toDouble(),
                    ),
                  ),
                ),
            ],
          ),
          ),
        ),
        // Volume chart
        if (_showVolume && volumeGroups.isNotEmpty)
          SizedBox(
            height: 80,
            child: BarChart(
              BarChartData(
                barGroups: volumeGroups,
                borderData: FlBorderData(show: false),
                gridData: const FlGridData(show: false),
                titlesData: const FlTitlesData(
                  show: false,
                ),
                barTouchData: BarTouchData(
                  enabled: true,
                  touchTooltipData: BarTouchTooltipData(
                    getTooltipColor: (_) => theme.colorScheme.surfaceContainerHighest,
                    getTooltipItem: (group, groupIndex, rod, rodIndex) {
                      if (groupIndex < 0 || groupIndex >= entries.length) {
                        return null;
                      }
                      final entry = entries[groupIndex];
                      return BarTooltipItem(
                        '${entry.date.month}/${entry.date.day}\n',
                        TextStyle(
                          color: theme.colorScheme.onSurface,
                          fontWeight: FontWeight.bold,
                        ),
                        children: [
                          TextSpan(
                            text: 'Vol: ${_formatVolume(entry.volume)}',
                            style: theme.textTheme.labelSmall,
                          ),
                        ],
                      );
                    },
                  ),
                ),
                minY: 0,
                maxY: maxVolume,
                alignment: BarChartAlignment.spaceAround,
              ),
            ),
          ),
        // MACD chart
        if (_showMacd)
          SizedBox(
            height: 100,
            child: _MacdChartWidget(
              macd: macd,
              entries: entries,
              minY: macdMin,
              maxY: macdMax,
            ),
          ),
      ],
    );
  }

  static String _formatPriceAxis(double v) {
    if (v >= 1e9) return '${(v / 1e9).toStringAsFixed(1)}B';
    if (v >= 1e6) return '${(v / 1e6).toStringAsFixed(1)}M';
    if (v >= 1e3) return '${(v / 1e3).toStringAsFixed(1)}K';
    return v.toStringAsFixed(0);
  }

  static String _formatPrice(double v) {
    if (v >= 1e9) return '${(v / 1e9).toStringAsFixed(2)}B';
    if (v >= 1e6) return '${(v / 1e6).toStringAsFixed(2)}M';
    if (v >= 1e3) return '${(v / 1e3).toStringAsFixed(2)}K';
    return v.toStringAsFixed(2);
  }

  static String _formatVolume(int v) {
    if (v >= 1e6) return '${(v / 1e6).toStringAsFixed(2)}M';
    if (v >= 1e3) return '${(v / 1e3).toStringAsFixed(1)}K';
    return v.toString();
  }

  static double _calculateNiceInterval(double range) {
    if (range <= 0) return 1;
    final magnitude = pow(10, (log(range) / ln10).floor());
    final residual = range / magnitude;
    double nice;
    if (residual <= 1.5) {
      nice = 1;
    } else if (residual <= 3) {
      nice = 2;
    } else if (residual <= 7) {
      nice = 5;
    } else {
      nice = 10;
    }
    return nice * magnitude;
  }

  static double _calculateBottomInterval(int length) {
    if (length <= 10) return 2;
    if (length <= 30) return 5;
    if (length <= 60) return 7;
    if (length <= 90) return 10;
    if (length <= 180) return 15;
    if (length <= 365) return 30;
    return 60;
  }
}

class _LineChartWidget extends StatelessWidget {
  final List<MarketHistoryEntry> entries;
  const _LineChartWidget({required this.entries});

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);

    double minPrice = double.infinity;
    double maxPrice = double.negativeInfinity;
    for (final entry in entries) {
      if (entry.average < minPrice) minPrice = entry.average;
      if (entry.average > maxPrice) maxPrice = entry.average;
    }

    final padding = (maxPrice - minPrice) * 0.05;
    minPrice -= padding;
    maxPrice += padding;

    final spots = <FlSpot>[];
    for (int i = 0; i < entries.length; i++) {
      spots.add(FlSpot(i.toDouble(), entries[i].average));
    }

    return Padding(
      padding: const EdgeInsets.all(12),
      child: LineChart(
        LineChartData(
          gridData: FlGridData(
            show: true,
            drawVerticalLine: true,
            drawHorizontalLine: true,
            horizontalInterval:
                _calculateNiceInterval(maxPrice - minPrice),
            getDrawingHorizontalLine: (value) => FlLine(
              color: theme.colorScheme.onSurfaceVariant.withValues(alpha: 0.1),
              strokeWidth: 1,
            ),
            getDrawingVerticalLine: (value) => FlLine(
              color: theme.colorScheme.onSurfaceVariant.withValues(alpha: 0.1),
              strokeWidth: 1,
            ),
          ),
          titlesData: FlTitlesData(
            show: true,
            rightTitles: const AxisTitles(
              sideTitles: SideTitles(showTitles: false),
            ),
            topTitles: const AxisTitles(
              sideTitles: SideTitles(showTitles: false),
            ),
            bottomTitles: AxisTitles(
              sideTitles: SideTitles(
                showTitles: true,
                reservedSize: 30,
                interval: _calculateBottomInterval(
                    entries.length),
                getTitlesWidget: (value, meta) {
                  final index = value.toInt();
                  if (index < 0 || index >= entries.length) {
                    return const SizedBox.shrink();
                  }
                  final date = entries[index].date;
                  return Padding(
                    padding: const EdgeInsets.only(top: 8),
                    child: Text(
                      '${date.month}/${date.day}',
                      style: theme.textTheme.labelSmall?.copyWith(
                        color: theme.colorScheme.onSurfaceVariant,
                        fontSize: 10,
                      ),
                    ),
                  );
                },
              ),
            ),
            leftTitles: AxisTitles(
              sideTitles: SideTitles(
                showTitles: true,
                reservedSize: 60,
                interval: _calculateNiceInterval(
                    maxPrice - minPrice),
                getTitlesWidget: (value, meta) {
                  return Padding(
                    padding: const EdgeInsets.only(right: 8),
                    child: Text(
                      _formatPriceAxis(value),
                      style: theme.textTheme.labelSmall?.copyWith(
                        color: theme.colorScheme.onSurfaceVariant,
                        fontSize: 10,
                      ),
                    ),
                  );
                },
              ),
            ),
          ),
          borderData: FlBorderData(
            show: true,
            border: Border.all(
              color: theme.colorScheme.onSurfaceVariant.withValues(alpha: 0.2),
              width: 1,
            ),
          ),
          lineTouchData: LineTouchData(
            enabled: true,
            touchTooltipData: LineTouchTooltipData(
              getTooltipColor: (spot) => theme.colorScheme.surfaceContainerHighest,
              getTooltipItems: (touchedSpots) {
                if (touchedSpots.isEmpty || touchedSpots.first.x.toInt() >= entries.length) {
                  return <LineTooltipItem>[];
                }
                final entry = entries[touchedSpots.first.x.toInt()];
                return touchedSpots.map((spot) {
                  return LineTooltipItem(
                    '${entry.date.month}/${entry.date.day}\n',
                    TextStyle(
                      color: theme.colorScheme.onSurface,
                      fontWeight: FontWeight.bold,
                    ),
                    children: [
                      TextSpan(
                        text: 'Avg: ${_formatPrice(entry.average)}',
                        style: theme.textTheme.labelSmall,
                      ),
                    ],
                  );
                }).toList();
              },
            ),
          ),
          lineBarsData: [
            LineChartBarData(
              spots: spots,
              isCurved: true,
              curveSmoothness: 0.1,
              color: theme.colorScheme.primary,
              barWidth: 2,
              dotData: const FlDotData(show: false),
              belowBarData: BarAreaData(
                show: true,
                gradient: LinearGradient(
                  colors: [
                    theme.colorScheme.primary.withValues(alpha: 0.3),
                    theme.colorScheme.primary.withValues(alpha: 0.05),
                  ],
                  begin: Alignment.topCenter,
                  end: Alignment.bottomCenter,
                ),
              ),
            ),
          ],
          minY: minPrice,
          maxY: maxPrice,
          minX: 0,
          maxX: (entries.length - 1).toDouble(),
        ),
      ),
    );
  }

  static String _formatPrice(double v) {
    if (v >= 1e9) return '${(v / 1e9).toStringAsFixed(2)}B';
    if (v >= 1e6) return '${(v / 1e6).toStringAsFixed(2)}M';
    if (v >= 1e3) return '${(v / 1e3).toStringAsFixed(2)}K';
    return v.toStringAsFixed(2);
  }

  static String _formatPriceAxis(double v) {
    if (v >= 1e9) return '${(v / 1e9).toStringAsFixed(1)}B';
    if (v >= 1e6) return '${(v / 1e6).toStringAsFixed(1)}M';
    if (v >= 1e3) return '${(v / 1e3).toStringAsFixed(1)}K';
    return v.toStringAsFixed(0);
  }

  static double _calculateNiceInterval(double range) {
    if (range <= 0) return 1;
    final magnitude = pow(10, (log(range) / ln10).floor());
    final residual = range / magnitude;
    double nice;
    if (residual <= 1.5) {
      nice = 1;
    } else if (residual <= 3) {
      nice = 2;
    } else if (residual <= 7) {
      nice = 5;
    } else {
      nice = 10;
    }
    return nice * magnitude;
  }

  static double _calculateBottomInterval(int length) {
    if (length <= 10) return 2;
    if (length <= 30) return 5;
    if (length <= 60) return 7;
    if (length <= 90) return 10;
    if (length <= 180) return 15;
    if (length <= 365) return 30;
    return 60;
  }
}


// ---------------------------------------------------------------------------
// MACD Chart Widget
// ---------------------------------------------------------------------------

class _MacdChartWidget extends StatelessWidget {
  final MacdResult macd;
  final List<MarketHistoryEntry> entries;
  final double minY;
  final double maxY;

  const _MacdChartWidget({
    required this.macd,
    required this.entries,
    required this.minY,
    required this.maxY,
  });

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);

    final barGroups = <BarChartGroupData>[];
    final macdLine = <FlSpot>[];
    final signalLine = <FlSpot>[];

    for (int i = 0; i < macd.macd.length; i++) {
      final x = i.toDouble();

      // Histogram bars
      if (macd.histogram[i] != null) {
        final isPositive = macd.histogram[i]! >= 0;
        barGroups.add(
          BarChartGroupData(
            x: i,
            barRods: [
              BarChartRodData(
                toY: macd.histogram[i]!,
                fromY: 0,
                color: isPositive
                    ? Colors.green.withValues(alpha: 0.6)
                    : Colors.red.withValues(alpha: 0.6),
                width: 8,
                borderRadius: const BorderRadius.vertical(top: Radius.circular(1)),
              ),
            ],
          ),
        );
      }

      // MACD line
      if (macd.macd[i] != null) {
        macdLine.add(FlSpot(x, macd.macd[i]!));
      }

      // Signal line
      if (macd.signal[i] != null) {
        signalLine.add(FlSpot(x, macd.signal[i]!));
      }
    }

    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Padding(
          padding: const EdgeInsets.only(left: 12, top: 4),
          child: Text(
            'MACD(5,15,5)',
            style: theme.textTheme.labelSmall?.copyWith(
              color: theme.colorScheme.onSurfaceVariant,
            ),
          ),
        ),
        Expanded(
          child: InteractiveViewer(
            panEnabled: true,
            scaleEnabled: true,
            minScale: 0.5,
            maxScale: 10,
            boundaryMargin: const EdgeInsets.all(20),
            child: Stack(
            children: [
              BarChart(
                BarChartData(
                  barGroups: barGroups,
                  borderData: FlBorderData(
                    show: true,
                    border: Border.all(
                      color: theme.colorScheme.onSurfaceVariant.withValues(alpha: 0.2),
                      width: 1,
                    ),
                  ),
                  gridData: FlGridData(
                    show: true,
                    drawVerticalLine: false,
                    drawHorizontalLine: true,
                    horizontalInterval: _calculateMacdNiceInterval(maxY - minY),
                    getDrawingHorizontalLine: (value) => FlLine(
                      color: theme.colorScheme.onSurfaceVariant.withValues(alpha: 0.1),
                      strokeWidth: 1,
                    ),
                  ),
                  titlesData: FlTitlesData(
                    show: true,
                    rightTitles: const AxisTitles(
                      sideTitles: SideTitles(showTitles: false),
                    ),
                    topTitles: const AxisTitles(
                      sideTitles: SideTitles(showTitles: false),
                    ),
                    bottomTitles: AxisTitles(
                      sideTitles: SideTitles(
                        showTitles: true,
                        reservedSize: 30,
                        interval: _calculateMacdBottomInterval(entries.length),
                        getTitlesWidget: (value, meta) {
                          final index = value.toInt();
                          final interval = _calculateMacdBottomInterval(entries.length);
                          if (index % interval != 0) return const SizedBox.shrink();
                          if (index < 0 || index >= entries.length) {
                            return const SizedBox.shrink();
                          }
                          final date = entries[index].date;
                          return Padding(
                            padding: const EdgeInsets.only(top: 8),
                            child: Text(
                              '${date.month}/${date.day}',
                              style: theme.textTheme.labelSmall?.copyWith(
                                color: theme.colorScheme.onSurfaceVariant,
                                fontSize: 10,
                              ),
                            ),
                          );
                        },
                      ),
                    ),
                    leftTitles: AxisTitles(
                      sideTitles: SideTitles(
                        showTitles: true,
                        reservedSize: 50,
                        interval: _calculateMacdNiceInterval(maxY - minY),
                        getTitlesWidget: (value, meta) {
                          return Padding(
                            padding: const EdgeInsets.only(right: 8),
                            child: Text(
                              _formatMacdValue(value),
                              style: theme.textTheme.labelSmall?.copyWith(
                                color: theme.colorScheme.onSurfaceVariant,
                                fontSize: 10,
                              ),
                            ),
                          );
                        },
                      ),
                    ),
                  ),
                  barTouchData: BarTouchData(enabled: false),
                  minY: minY,
                  maxY: maxY,
                  alignment: BarChartAlignment.spaceAround,
                ),
              ),
              // MACD line overlay
              if (macdLine.isNotEmpty)
                LineChart(
                  LineChartData(
                    lineBarsData: [
                      LineChartBarData(
                        spots: macdLine,
                        isCurved: false,
                        color: Colors.blue,
                        barWidth: 1.5,
                        dotData: const FlDotData(show: false),
                        belowBarData: BarAreaData(show: false),
                      ),
                      LineChartBarData(
                        spots: signalLine,
                        isCurved: false,
                        color: Colors.orange,
                        barWidth: 1.5,
                        dotData: const FlDotData(show: false),
                        belowBarData: BarAreaData(show: false),
                      ),
                    ],
                    borderData: FlBorderData(show: false),
                    gridData: const FlGridData(show: false),
                    titlesData: const FlTitlesData(show: false),
                    lineTouchData: LineTouchData(enabled: false),
                    minY: minY,
                    maxY: maxY,
                    minX: 0,
                    maxX: (entries.length - 1).toDouble(),
                  ),
                ),
            ],
          ),
          ),
        ),
      ],
    );
  }

  static String _formatMacdValue(double v) {
    if (v.abs() >= 1e6) return '${(v / 1e6).toStringAsFixed(1)}M';
    if (v.abs() >= 1e3) return '${(v / 1e3).toStringAsFixed(1)}K';
    return v.toStringAsFixed(0);
  }

  static double _calculateMacdNiceInterval(double range) {
    if (range <= 0) return 1;
    final magnitude = pow(10, (log(range) / ln10).floor());
    final residual = range / magnitude;
    double nice;
    if (residual <= 1.5) {
      nice = 1;
    } else if (residual <= 3) {
      nice = 2;
    } else if (residual <= 7) {
      nice = 5;
    } else {
      nice = 10;
    }
    return nice * magnitude;
  }

  static double _calculateMacdBottomInterval(int length) {
    if (length <= 10) return 2;
    if (length <= 30) return 5;
    if (length <= 60) return 7;
    if (length <= 90) return 10;
    if (length <= 180) return 15;
    if (length <= 365) return 30;
    return 60;
  }
}
