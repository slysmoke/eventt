import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../core/esi/esi_provider.dart';
import '../../../core/sde/sde_models.dart';
import '../../../core/sde/sde_provider.dart';
import '../../market_browser/data/market_order_repository.dart';
import '../domain/import_params.dart';
import '../domain/inter_region_computer.dart';
import '../domain/inter_region_row.dart';

// ---------------------------------------------------------------------------
// Providers
// ---------------------------------------------------------------------------

final _regionOrdersRepoProvider = Provider<MarketOrderRepository>((ref) =>
    MarketOrderRepository(esi: ref.watch(esiClientProvider)));

// ---------------------------------------------------------------------------
// Analysis state
// ---------------------------------------------------------------------------

enum _Status { idle, fetching, ready, error }
enum _SortCol {
  name, srcRegion, srcBuy, srcSell, srcOrders, srcBuyout,
  dstRegion, dstBuy, dstSell, dstOrders, dstBuyout,
  difference, volume, margin
}

class _AnalysisState {
  final _Status status;
  final String? error;
  final List<InterRegionRow> rows;
  final _SortCol sortCol;
  final bool sortAsc;

  const _AnalysisState({
    this.status = _Status.idle,
    this.error,
    this.rows = const [],
    this.sortCol = _SortCol.margin,
    this.sortAsc = false,
  });

  _AnalysisState copyWith({
    _Status? status,
    String? error,
    List<InterRegionRow>? rows,
    _SortCol? sortCol,
    bool? sortAsc,
  }) =>
      _AnalysisState(
        status: status ?? this.status,
        error: error ?? this.error,
        rows: rows ?? this.rows,
        sortCol: sortCol ?? this.sortCol,
        sortAsc: sortAsc ?? this.sortAsc,
      );

  List<InterRegionRow> get sortedRows {
    final sorted = List<InterRegionRow>.from(rows);
    sorted.sort((a, b) {
      final cmp = switch (sortCol) {
        _SortCol.name => a.typeName.compareTo(b.typeName),
        _SortCol.srcRegion => a.srcRegionName.compareTo(b.srcRegionName),
        _SortCol.srcBuy => a.srcBuyPrice.compareTo(b.srcBuyPrice),
        _SortCol.srcSell => a.srcSellPrice.compareTo(b.srcSellPrice),
        _SortCol.srcOrders => a.srcOrderCount.compareTo(b.srcOrderCount),
        _SortCol.srcBuyout => a.srcSellBuyout.compareTo(b.srcSellBuyout),
        _SortCol.dstRegion => a.dstRegionName.compareTo(b.dstRegionName),
        _SortCol.dstBuy => a.dstBuyPrice.compareTo(b.dstBuyPrice),
        _SortCol.dstSell => a.dstSellPrice.compareTo(b.dstSellPrice),
        _SortCol.dstOrders => a.dstOrderCount.compareTo(b.dstOrderCount),
        _SortCol.dstBuyout => a.dstSellBuyout.compareTo(b.dstSellBuyout),
        _SortCol.difference => a.difference.compareTo(b.difference),
        _SortCol.volume => a.volume.compareTo(b.volume),
        _SortCol.margin => a.margin.compareTo(b.margin),
      };
      return sortAsc ? cmp : -cmp;
    });
    return sorted;
  }
}

class _AnalysisNotifier extends Notifier<_AnalysisState> {
  @override
  _AnalysisState build() => const _AnalysisState();

  void toggleSort(_SortCol col) {
    final current = state;
    if (current.sortCol == col) {
      state = current.copyWith(sortAsc: !current.sortAsc);
    } else {
      state = current.copyWith(sortCol: col, sortAsc: col == _SortCol.name);
    }
  }

  Future<void> analyze({
    required MapRegion srcRegion,
    required MapRegion dstRegion,
    required PriceType srcPriceType,
    required PriceType dstPriceType,
  }) async {
    final repo = ref.read(_regionOrdersRepoProvider);
    final sde = ref.read(sdeDatabaseProvider);

    state = state.copyWith(
      status: _Status.fetching,
      error: null,
    );

    try {
      // Fetch orders for both regions
      // Note: ESI doesn't have "all types" endpoint, fetch from SDE
      final allTypeIds = sde?.getAllPublishedTypeIds() ?? [];

      // Fetch in batches
      final srcAllOrders = <MarketOrder>[];
      final dstAllOrders = <MarketOrder>[];

      for (var i = 0; i < allTypeIds.length; i += 100) {
        final batch = allTypeIds.skip(i).take(100);
        final srcFutures = batch.map((typeId) =>
            repo.fetchOrders(srcRegion.regionId, typeId));
        final dstFutures = batch.map((typeId) =>
            repo.fetchOrders(dstRegion.regionId, typeId));

        final srcResults = await Future.wait(srcFutures);
        final dstResults = await Future.wait(dstFutures);

        for (final orders in srcResults) {
          srcAllOrders.addAll(orders);
        }
        for (final orders in dstResults) {
          dstAllOrders.addAll(orders);
        }
      }

      // Lookup type names
      final allIds = {
        ...srcAllOrders.map((o) => o.typeId),
        ...dstAllOrders.map((o) => o.typeId),
      }.toList();
      final typeInfo = sde != null
          ? sde.getTypesByIds(allIds)
          : <int, InvType>{};

      final rows = InterRegionAnalysisComputer.compute(
        srcOrders: srcAllOrders,
        dstOrders: dstAllOrders,
        srcRegion: srcRegion,
        dstRegion: dstRegion,
        typeInfo: typeInfo,
        srcPriceType: srcPriceType,
        dstPriceType: dstPriceType,
      );

      state = state.copyWith(
        status: _Status.ready,
        rows: rows,
      );
    } catch (e) {
      state = state.copyWith(
        status: _Status.error,
        error: e.toString(),
      );
    }
  }
}

final _analysisProvider =
    NotifierProvider<_AnalysisNotifier, _AnalysisState>(_AnalysisNotifier.new);

// ---------------------------------------------------------------------------
// Inter-Region Tab Widget
// ---------------------------------------------------------------------------

class InterRegionAnalysisTab extends ConsumerStatefulWidget {
  const InterRegionAnalysisTab({super.key});

  @override
  ConsumerState<InterRegionAnalysisTab> createState() =>
      _InterRegionAnalysisTabState();
}

class _InterRegionAnalysisTabState
    extends ConsumerState<InterRegionAnalysisTab> {
  int? _srcRegionId;
  int? _dstRegionId;
  PriceType _srcPriceType = PriceType.sell;
  PriceType _dstPriceType = PriceType.sell;

  void _analyze() {
    if (_srcRegionId == null || _dstRegionId == null) return;
    final db = ref.read(sdeDatabaseProvider);
    if (db == null) return;

    final regions = db.getRegions();
    final srcRegion = regions.firstWhere((r) => r.regionId == _srcRegionId);
    final dstRegion = regions.firstWhere((r) => r.regionId == _dstRegionId);

    ref.read(_analysisProvider.notifier).analyze(
          srcRegion: srcRegion,
          dstRegion: dstRegion,
          srcPriceType: _srcPriceType,
          dstPriceType: _dstPriceType,
        );
  }

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final state = ref.watch(_analysisProvider);
    final sdeState = ref.watch(sdeInitProvider);
    final db = ref.watch(sdeDatabaseProvider);

    // SDE loading state
    if (sdeState.isDownloading || (!sdeState.isReady && sdeState.error == null)) {
      return Center(
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            const Icon(Icons.download_rounded, size: 48),
            const SizedBox(height: 16),
            Text(
              sdeState.isDownloading ? 'Downloading EVE data…' : 'Checking EVE data…',
              style: theme.textTheme.titleMedium,
            ),
          ],
        ),
      );
    }

    if (sdeState.error != null) {
      return Center(
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            Icon(Icons.error_outline, size: 48, color: theme.colorScheme.error),
            const SizedBox(height: 16),
            Text('Failed to load EVE data', style: theme.textTheme.titleMedium),
            const SizedBox(height: 8),
            Text(sdeState.error!, textAlign: TextAlign.center,
                style: theme.textTheme.bodySmall),
          ],
        ),
      );
    }

    if (db == null) return const SizedBox.shrink();

    final regions = db.getRegions();

    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        // ── Control bar ──────────────────────────────────────────────
        Padding(
          padding: const EdgeInsets.fromLTRB(12, 8, 12, 6),
          child: Wrap(
            spacing: 16,
            runSpacing: 6,
            crossAxisAlignment: WrapCrossAlignment.center,
            children: [
              // Source region
              Row(mainAxisSize: MainAxisSize.min, children: [
                Text('Src:', style: theme.textTheme.bodySmall),
                const SizedBox(width: 6),
                DropdownButton<int>(
                  value: _srcRegionId,
                  isDense: true,
                  hint: const Text('Source'),
                  items: regions
                      .map((r) => DropdownMenuItem(
                            value: r.regionId,
                            child: Text(r.regionName),
                          ))
                      .toList(),
                  onChanged: (id) => setState(() => _srcRegionId = id),
                ),
              ]),
              const Icon(Icons.arrow_forward, size: 16),
              // Destination region
              Row(mainAxisSize: MainAxisSize.min, children: [
                Text('Dst:', style: theme.textTheme.bodySmall),
                const SizedBox(width: 6),
                DropdownButton<int>(
                  value: _dstRegionId,
                  isDense: true,
                  hint: const Text('Dest'),
                  items: regions
                      .map((r) => DropdownMenuItem(
                            value: r.regionId,
                            child: Text(r.regionName),
                          ))
                      .toList(),
                  onChanged: (id) => setState(() => _dstRegionId = id),
                ),
              ]),
              const SizedBox(width: 8),
              // Price type selectors
              _PriceTypeSelector(
                label: 'Src',
                value: _srcPriceType,
                onChanged: (v) => setState(() => _srcPriceType = v),
              ),
              _PriceTypeSelector(
                label: 'Dst',
                value: _dstPriceType,
                onChanged: (v) => setState(() => _dstPriceType = v),
              ),
              FilledButton.icon(
                onPressed: (_srcRegionId == null ||
                            _dstRegionId == null ||
                            state.status == _Status.fetching)
                    ? null
                    : _analyze,
                icon: const Icon(Icons.search, size: 16),
                label: const Text('Analyse'),
                style: FilledButton.styleFrom(
                  padding:
                      const EdgeInsets.symmetric(horizontal: 14, vertical: 6),
                  minimumSize: Size.zero,
                  tapTargetSize: MaterialTapTargetSize.shrinkWrap,
                ),
              ),
            ],
          ),
        ),
        const Divider(height: 1),

        // ── Content ──────────────────────────────────────────────────
        Expanded(child: _Body(state: state)),
      ],
    );
  }
}

// ---------------------------------------------------------------------------
// Price type selector
// ---------------------------------------------------------------------------

class _PriceTypeSelector extends StatelessWidget {
  final String label;
  final PriceType value;
  final void Function(PriceType) onChanged;

  const _PriceTypeSelector({
    required this.label,
    required this.value,
    required this.onChanged,
  });

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    return Row(
      mainAxisSize: MainAxisSize.min,
      children: [
        Text('$label:', style: theme.textTheme.bodySmall),
        const SizedBox(width: 4),
        DropdownButton<PriceType>(
          value: value,
          isDense: true,
          items: PriceType.values
              .map((t) => DropdownMenuItem(
                    value: t,
                    child: Text(
                      t == PriceType.sell ? 'Sell' : 'Buy',
                      style: theme.textTheme.bodySmall,
                    ),
                  ))
              .toList(),
          onChanged: (v) {
            if (v != null) onChanged(v);
          },
        ),
      ],
    );
  }
}

// ---------------------------------------------------------------------------
// Body
// ---------------------------------------------------------------------------

class _Body extends ConsumerWidget {
  final _AnalysisState state;
  const _Body({required this.state});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final theme = Theme.of(context);

    return switch (state.status) {
      _Status.idle => Center(
          child: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              Icon(Icons.compare_arrows,
                  size: 48, color: theme.colorScheme.onSurfaceVariant),
              const SizedBox(height: 16),
              Text(
                'Select source and destination regions, then press Analyse',
                style: theme.textTheme.bodyMedium?.copyWith(
                    color: theme.colorScheme.onSurfaceVariant),
              ),
            ],
          ),
        ),

      _Status.fetching => const Center(child: CircularProgressIndicator()),

      _Status.error => Center(
          child: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              Icon(Icons.error_outline,
                  size: 48, color: theme.colorScheme.error),
              const SizedBox(height: 12),
              Text(state.error ?? 'Unknown error',
                  textAlign: TextAlign.center,
                  style: theme.textTheme.bodySmall
                      ?.copyWith(color: theme.colorScheme.error)),
            ],
          ),
        ),

      _Status.ready => _ResultsTable(state: state),
    };
  }
}

// ---------------------------------------------------------------------------
// Results table
// ---------------------------------------------------------------------------

class _ResultsTable extends ConsumerWidget {
  final _AnalysisState state;
  const _ResultsTable({required this.state});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final theme = Theme.of(context);
    final rows = state.sortedRows;

    if (rows.isEmpty) {
      return Center(
        child: Text(
          'No common items found between these regions.',
          textAlign: TextAlign.center,
          style: theme.textTheme.bodyMedium
              ?.copyWith(color: theme.colorScheme.onSurfaceVariant),
        ),
      );
    }

    return Column(
      children: [
        // Header row
        _TableHeader(
          sortCol: state.sortCol,
          sortAsc: state.sortAsc,
          onSort: (col) =>
              ref.read(_analysisProvider.notifier).toggleSort(col),
        ),
        const Divider(height: 1),
        // Summary row
        Padding(
          padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 3),
          child: Text(
            '${rows.length} items',
            style: theme.textTheme.labelSmall
                ?.copyWith(color: theme.colorScheme.onSurfaceVariant),
          ),
        ),
        const Divider(height: 1),
        // Data rows
        Expanded(
          child: ListView.builder(
            itemCount: rows.length,
            itemExtent: 28,
            itemBuilder: (context, i) => _DataRow(row: rows[i]),
          ),
        ),
      ],
    );
  }
}

// ---------------------------------------------------------------------------
// Table header
// ---------------------------------------------------------------------------

class _TableHeader extends StatelessWidget {
  final _SortCol sortCol;
  final bool sortAsc;
  final void Function(_SortCol) onSort;

  const _TableHeader({
    required this.sortCol,
    required this.sortAsc,
    required this.onSort,
  });

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 4),
      child: Row(
        children: [
          _HeaderCell('Name', _SortCol.name, flex: 3,
              sortCol: sortCol, sortAsc: sortAsc, onSort: onSort),
          _HeaderCell('Src Buy', _SortCol.srcBuy, flex: 2,
              right: true, sortCol: sortCol, sortAsc: sortAsc, onSort: onSort),
          _HeaderCell('Src Sell', _SortCol.srcSell, flex: 2,
              right: true, sortCol: sortCol, sortAsc: sortAsc, onSort: onSort),
          _HeaderCell('Src #', _SortCol.srcOrders, flex: 1,
              right: true, sortCol: sortCol, sortAsc: sortAsc, onSort: onSort),
          _HeaderCell('Dst Buy', _SortCol.dstBuy, flex: 2,
              right: true, sortCol: sortCol, sortAsc: sortAsc, onSort: onSort),
          _HeaderCell('Dst Sell', _SortCol.dstSell, flex: 2,
              right: true, sortCol: sortCol, sortAsc: sortAsc, onSort: onSort),
          _HeaderCell('Dst #', _SortCol.dstOrders, flex: 1,
              right: true, sortCol: sortCol, sortAsc: sortAsc, onSort: onSort),
          _HeaderCell('Diff', _SortCol.difference, flex: 2,
              right: true, sortCol: sortCol, sortAsc: sortAsc, onSort: onSort),
          _HeaderCell('Volume', _SortCol.volume, flex: 2,
              right: true, sortCol: sortCol, sortAsc: sortAsc, onSort: onSort),
          _HeaderCell('Margin', _SortCol.margin, flex: 1,
              right: true, sortCol: sortCol, sortAsc: sortAsc, onSort: onSort),
        ],
      ),
    );
  }
}

class _HeaderCell extends StatelessWidget {
  final String label;
  final _SortCol col;
  final int flex;
  final bool right;
  final _SortCol sortCol;
  final bool sortAsc;
  final void Function(_SortCol) onSort;

  const _HeaderCell(
    this.label,
    this.col, {
    required this.flex,
    this.right = false,
    required this.sortCol,
    required this.sortAsc,
    required this.onSort,
  });

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final active = sortCol == col;
    final style = theme.textTheme.labelSmall?.copyWith(
      color: active
          ? theme.colorScheme.primary
          : theme.colorScheme.onSurfaceVariant,
      fontWeight: active ? FontWeight.w700 : null,
    );

    return Expanded(
      flex: flex,
      child: InkWell(
        onTap: () => onSort(col),
        child: Row(
          mainAxisAlignment:
              right ? MainAxisAlignment.end : MainAxisAlignment.start,
          children: [
            if (!right && active)
              Icon(
                sortAsc ? Icons.arrow_upward : Icons.arrow_downward,
                size: 10,
                color: theme.colorScheme.primary,
              ),
            Text(label, style: style),
            if (right && active)
              Icon(
                sortAsc ? Icons.arrow_upward : Icons.arrow_downward,
                size: 10,
                color: theme.colorScheme.primary,
              ),
          ],
        ),
      ),
    );
  }
}

// ---------------------------------------------------------------------------
// Data row
// ---------------------------------------------------------------------------

class _DataRow extends StatelessWidget {
  final InterRegionRow row;
  const _DataRow({required this.row});

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final marginColor = row.margin >= 20
        ? Colors.greenAccent
        : row.margin >= 10
            ? Colors.amber
            : theme.colorScheme.onSurface;

    return Padding(
      padding: const EdgeInsets.symmetric(horizontal: 12),
      child: Row(
        children: [
          // Name
          Expanded(
            flex: 3,
            child: Text(
              row.typeName,
              overflow: TextOverflow.ellipsis,
              style: theme.textTheme.bodySmall,
            ),
          ),
          // Src buy
          Expanded(
            flex: 2,
            child: Text(
              _fmt(row.srcBuyPrice),
              textAlign: TextAlign.right,
              style: theme.textTheme.bodySmall,
            ),
          ),
          // Src sell
          Expanded(
            flex: 2,
            child: Text(
              _fmt(row.srcSellPrice),
              textAlign: TextAlign.right,
              style: theme.textTheme.bodySmall,
            ),
          ),
          // Src order count
          Expanded(
            flex: 1,
            child: Text(
              row.srcOrderCount.toString(),
              textAlign: TextAlign.right,
              style: theme.textTheme.bodySmall?.copyWith(
                  color: theme.colorScheme.onSurfaceVariant),
            ),
          ),
          // Dst buy
          Expanded(
            flex: 2,
            child: Text(
              _fmt(row.dstBuyPrice),
              textAlign: TextAlign.right,
              style: theme.textTheme.bodySmall,
            ),
          ),
          // Dst sell
          Expanded(
            flex: 2,
            child: Text(
              _fmt(row.dstSellPrice),
              textAlign: TextAlign.right,
              style: theme.textTheme.bodySmall,
            ),
          ),
          // Dst order count
          Expanded(
            flex: 1,
            child: Text(
              row.dstOrderCount.toString(),
              textAlign: TextAlign.right,
              style: theme.textTheme.bodySmall?.copyWith(
                  color: theme.colorScheme.onSurfaceVariant),
            ),
          ),
          // Difference
          Expanded(
            flex: 2,
            child: Text(
              _fmt(row.difference),
              textAlign: TextAlign.right,
              style: theme.textTheme.bodySmall
                  ?.copyWith(color: Colors.greenAccent),
            ),
          ),
          // Volume
          Expanded(
            flex: 2,
            child: Text(
              _fmtVol(row.volume.toDouble()),
              textAlign: TextAlign.right,
              style: theme.textTheme.bodySmall?.copyWith(
                  color: theme.colorScheme.onSurfaceVariant),
            ),
          ),
          // Margin
          Expanded(
            flex: 1,
            child: Text(
              '${row.margin.toStringAsFixed(1)}%',
              textAlign: TextAlign.right,
              style: theme.textTheme.bodySmall
                  ?.copyWith(color: marginColor, fontWeight: FontWeight.w600),
            ),
          ),
        ],
      ),
    );
  }

  static String _fmt(double v) {
    if (v.abs() >= 1e9) return '${(v / 1e9).toStringAsFixed(2)}B';
    if (v.abs() >= 1e6) return '${(v / 1e6).toStringAsFixed(2)}M';
    if (v.abs() >= 1e3) return '${(v / 1e3).toStringAsFixed(1)}K';
    return v.toStringAsFixed(2);
  }

  static String _fmtVol(double v) {
    if (v.abs() >= 1e6) return '${(v / 1e6).toStringAsFixed(2)}M';
    if (v.abs() >= 1e3) return '${(v / 1e3).toStringAsFixed(1)}K';
    return v.toStringAsFixed(0);
  }
}
