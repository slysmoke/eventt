import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../core/esi/esi_provider.dart';
import '../../../core/sde/sde_models.dart';
import '../../../core/sde/sde_provider.dart';
import '../../market_browser/data/market_order_repository.dart';
import '../domain/import_params.dart';
import '../domain/scrapmetal_reprocessing_computer.dart';
import '../domain/scrapmetal_reprocessing_row.dart';

// ---------------------------------------------------------------------------
// Providers
// ---------------------------------------------------------------------------

final _scrapRepoProvider = Provider<MarketOrderRepository>((ref) {
  final db = ref.watch(sdeDatabaseProvider);
  if (db == null) {
    throw StateError('SDE database not yet available');
  }
  return MarketOrderRepository(
    esi: ref.watch(esiClientProvider),
    sde: db,
  );
});

// ---------------------------------------------------------------------------
// Analysis state
// ---------------------------------------------------------------------------

enum _Status { idle, fetching, ready, error }

enum _SortCol { name, volume, totalProfit, totalCost, difference, margin }

class _AnalysisState {
  final _Status status;
  final String? error;
  final List<ScrapmetalReprocessingRow> rows;
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
    List<ScrapmetalReprocessingRow>? rows,
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

  List<ScrapmetalReprocessingRow> get sortedRows {
    final sorted = List<ScrapmetalReprocessingRow>.from(rows);
    sorted.sort((a, b) {
      final cmp = switch (sortCol) {
        _SortCol.name => a.typeName.compareTo(b.typeName),
        _SortCol.volume => a.volume.compareTo(b.volume),
        _SortCol.totalProfit => a.totalProfit.compareTo(b.totalProfit),
        _SortCol.totalCost => a.totalCost.compareTo(b.totalCost),
        _SortCol.difference => a.difference.compareTo(b.difference),
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
    required int regionId,
    required ScrapmetalReprocessingParams params,
  }) async {
    final repo = ref.read(_scrapRepoProvider);
    final sde = ref.read(sdeDatabaseProvider);

    state = state.copyWith(status: _Status.fetching, error: null);

    try {
      final scrapInfo = sde?.getScrapmetalInfo() ?? <int, ScrapmetalInfo>{};
      if (scrapInfo.isEmpty) {
        state = state.copyWith(status: _Status.ready, rows: const []);
        return;
      }

      // Fetch orders for all scrapmetal types in batches of 20
      final typeIds = scrapInfo.keys.toList();
      final allOrders = <MarketOrder>[];

      for (var i = 0; i < typeIds.length; i += 20) {
        final batch = typeIds.skip(i).take(20);
        final futures =
            batch.map((id) => repo.fetchOrders(regionId, id));
        final results = await Future.wait(futures);
        for (final orders in results) {
          allOrders.addAll(orders);
        }
      }

      final allIds = allOrders.map((o) => o.typeId).toSet().toList();
      final typeInfo = sde != null ? sde.getTypesByIds(allIds) : <int, InvType>{};

      final rows = ScrapmetalReprocessingComputer.compute(
        srcOrders: allOrders,
        dstOrders: allOrders, // same region: same order book for minerals
        scrapmetalInfo: scrapInfo,
        typeInfo: typeInfo,
        params: params,
      );

      state = state.copyWith(status: _Status.ready, rows: rows);
    } catch (e) {
      state = state.copyWith(status: _Status.error, error: e.toString());
    }
  }
}

final _analysisProvider = NotifierProvider<_AnalysisNotifier, _AnalysisState>(
    _AnalysisNotifier.new);

// ---------------------------------------------------------------------------
// Scrapmetal Reprocessing Tab Widget
// ---------------------------------------------------------------------------

class ScrapmetalReprocessingTab extends ConsumerStatefulWidget {
  const ScrapmetalReprocessingTab({super.key});

  @override
  ConsumerState<ScrapmetalReprocessingTab> createState() =>
      _ScrapmetalReprocessingTabState();
}

class _ScrapmetalReprocessingTabState
    extends ConsumerState<ScrapmetalReprocessingTab> {
  int? _selectedRegionId;
  double _stationEfficiency = 0.5;
  int _scrapmetalSkill = 0;
  PriceType _dstPriceType = PriceType.buy;

  void _analyze() {
    if (_selectedRegionId == null) return;
    ref.read(_analysisProvider.notifier).analyze(
          regionId: _selectedRegionId!,
          params: ScrapmetalReprocessingParams(
            stationEfficiency: _stationEfficiency,
            scrapmetalSkillLevel: _scrapmetalSkill,
            dstPriceType: _dstPriceType,
          ),
        );
  }

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final state = ref.watch(_analysisProvider);
    final sdeState = ref.watch(sdeInitProvider);
    final db = ref.watch(sdeDatabaseProvider);

    if (sdeState.isDownloading ||
        (!sdeState.isReady && sdeState.error == null)) {
      return Center(
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            const Icon(Icons.download_rounded, size: 48),
            const SizedBox(height: 16),
            Text(
              sdeState.isDownloading
                  ? 'Downloading EVE data…'
                  : 'Checking EVE data…',
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
            Icon(Icons.error_outline,
                size: 48, color: theme.colorScheme.error),
            const SizedBox(height: 16),
            Text('Failed to load EVE data',
                style: theme.textTheme.titleMedium),
            const SizedBox(height: 8),
            Text(sdeState.error!,
                textAlign: TextAlign.center,
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
              // Region
              Row(mainAxisSize: MainAxisSize.min, children: [
                Text('Region:', style: theme.textTheme.bodySmall),
                const SizedBox(width: 6),
                DropdownButton<int>(
                  value: _selectedRegionId,
                  isDense: true,
                  hint: const Text('Select region'),
                  items: regions
                      .map((r) => DropdownMenuItem(
                            value: r.regionId,
                            child: Text(r.regionName),
                          ))
                      .toList(),
                  onChanged: (id) => setState(() => _selectedRegionId = id),
                ),
              ]),
              // Station efficiency
              Row(mainAxisSize: MainAxisSize.min, children: [
                Text('Efficiency:', style: theme.textTheme.bodySmall),
                const SizedBox(width: 4),
                SizedBox(
                  width: 60,
                  child: _EfficiencyField(
                    value: _stationEfficiency,
                    onChanged: (v) => setState(() => _stationEfficiency = v),
                  ),
                ),
                Text('%', style: theme.textTheme.bodySmall),
              ]),
              // Scrapmetal Processing skill
              Row(mainAxisSize: MainAxisSize.min, children: [
                Text('Scrapmetal:', style: theme.textTheme.bodySmall),
                const SizedBox(width: 4),
                DropdownButton<int>(
                  value: _scrapmetalSkill,
                  isDense: true,
                  items: List.generate(6, (i) => i)
                      .map((i) => DropdownMenuItem(
                            value: i,
                            child: Text('$i'),
                          ))
                      .toList(),
                  onChanged: (v) =>
                      setState(() => _scrapmetalSkill = v ?? 0),
                ),
              ]),
              // Destination price type
              _PriceTypeSelector(
                label: 'Minerals',
                value: _dstPriceType,
                onChanged: (v) => setState(() => _dstPriceType = v),
              ),
              FilledButton.icon(
                onPressed: _selectedRegionId == null ||
                        state.status == _Status.fetching
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
        Expanded(child: _Body(state: state)),
      ],
    );
  }
}

// ---------------------------------------------------------------------------
// Efficiency field
// ---------------------------------------------------------------------------

class _EfficiencyField extends StatefulWidget {
  final double value;
  final void Function(double) onChanged;
  const _EfficiencyField({required this.value, required this.onChanged});

  @override
  State<_EfficiencyField> createState() => _EfficiencyFieldState();
}

class _EfficiencyFieldState extends State<_EfficiencyField> {
  late final TextEditingController _ctrl;

  @override
  void initState() {
    super.initState();
    _ctrl = TextEditingController(
        text: (widget.value * 100).toStringAsFixed(0));
  }

  @override
  void didUpdateWidget(_EfficiencyField old) {
    super.didUpdateWidget(old);
    if (old.value != widget.value) {
      _ctrl.text = (widget.value * 100).toStringAsFixed(0);
    }
  }

  @override
  void dispose() {
    _ctrl.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    return TextField(
      controller: _ctrl,
      decoration: const InputDecoration(
        isDense: true,
        border: OutlineInputBorder(),
        contentPadding: EdgeInsets.symmetric(horizontal: 6, vertical: 4),
      ),
      style: theme.textTheme.bodySmall,
      keyboardType:
          const TextInputType.numberWithOptions(decimal: true),
      onChanged: (text) {
        final v = double.tryParse(text);
        if (v != null && v >= 0 && v <= 100) {
          widget.onChanged(v / 100);
        }
      },
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
              Icon(Icons.recycling,
                  size: 48, color: theme.colorScheme.onSurfaceVariant),
              const SizedBox(height: 16),
              Text(
                'Select a region and press Analyse',
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
          'No profitable scrapmetal reprocessing opportunities found.',
          textAlign: TextAlign.center,
          style: theme.textTheme.bodyMedium
              ?.copyWith(color: theme.colorScheme.onSurfaceVariant),
        ),
      );
    }

    return Column(
      children: [
        _TableHeader(
          sortCol: state.sortCol,
          sortAsc: state.sortAsc,
          onSort: (col) =>
              ref.read(_analysisProvider.notifier).toggleSort(col),
        ),
        const Divider(height: 1),
        Padding(
          padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 3),
          child: Text(
            '${rows.length} items',
            style: theme.textTheme.labelSmall
                ?.copyWith(color: theme.colorScheme.onSurfaceVariant),
          ),
        ),
        const Divider(height: 1),
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
          _HeaderCell('Name', _SortCol.name, flex: 4,
              sortCol: sortCol, sortAsc: sortAsc, onSort: onSort),
          _HeaderCell('Volume', _SortCol.volume, flex: 2,
              right: true, sortCol: sortCol, sortAsc: sortAsc, onSort: onSort),
          _HeaderCell('Mineral Value', _SortCol.totalProfit, flex: 2,
              right: true, sortCol: sortCol, sortAsc: sortAsc, onSort: onSort),
          _HeaderCell('Item Cost', _SortCol.totalCost, flex: 2,
              right: true, sortCol: sortCol, sortAsc: sortAsc, onSort: onSort),
          _HeaderCell('Profit', _SortCol.difference, flex: 2,
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
  final ScrapmetalReprocessingRow row;
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
          Expanded(
            flex: 4,
            child: Text(
              row.typeName,
              overflow: TextOverflow.ellipsis,
              style: theme.textTheme.bodySmall,
            ),
          ),
          Expanded(
            flex: 2,
            child: Text(
              _fmtVol(row.volume.toDouble()),
              textAlign: TextAlign.right,
              style: theme.textTheme.bodySmall
                  ?.copyWith(color: theme.colorScheme.onSurfaceVariant),
            ),
          ),
          Expanded(
            flex: 2,
            child: Text(
              _fmt(row.totalProfit),
              textAlign: TextAlign.right,
              style: theme.textTheme.bodySmall
                  ?.copyWith(color: Colors.greenAccent),
            ),
          ),
          Expanded(
            flex: 2,
            child: Text(
              _fmt(row.totalCost),
              textAlign: TextAlign.right,
              style: theme.textTheme.bodySmall
                  ?.copyWith(color: theme.colorScheme.onSurfaceVariant),
            ),
          ),
          Expanded(
            flex: 2,
            child: Text(
              _fmt(row.difference),
              textAlign: TextAlign.right,
              style: theme.textTheme.bodySmall
                  ?.copyWith(color: Colors.greenAccent),
            ),
          ),
          Expanded(
            flex: 1,
            child: Text(
              '${row.margin.toStringAsFixed(1)}%',
              textAlign: TextAlign.right,
              style: theme.textTheme.bodySmall?.copyWith(
                  color: marginColor, fontWeight: FontWeight.w600),
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
