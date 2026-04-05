import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../core/esi/esi_provider.dart';
import '../../../core/sde/sde_models.dart';
import '../../../core/sde/sde_provider.dart';
import '../data/region_orders_repository.dart';
import '../domain/import_analysis_computer.dart';
import '../domain/import_params.dart';
import '../domain/import_row.dart';

// ---------------------------------------------------------------------------
// Trade hubs
// ---------------------------------------------------------------------------

class _Hub {
  final String name;
  final int regionId;
  final int stationId;

  const _Hub(this.name, this.regionId, this.stationId);
}

const _hubs = [
  _Hub('Jita 4-4',     10000002, 60003760),
  _Hub('Amarr VIII',   10000043, 60008494),
  _Hub('Dodixie IX',   10000032, 60011866),
  _Hub('Rens VI',      10000030, 60004588),
  _Hub('Hek VIII',     10000042, 60005686),
];

// ---------------------------------------------------------------------------
// Providers
// ---------------------------------------------------------------------------

final _regionOrdersRepoProvider = Provider<RegionOrdersRepository>((ref) =>
    RegionOrdersRepository(esi: ref.watch(esiClientProvider)));

// ---------------------------------------------------------------------------
// Analysis state
// ---------------------------------------------------------------------------

enum _Status { idle, fetching, ready, error }
enum _SortCol {
  name, srcPrice, dstPrice, importPrice, priceDiff, margin,
  srcOrders, dstOrders, dstVolume, projectedProfit
}

class _AnalysisState {
  final _Status status;
  final String fetchMessage;
  final double fetchProgress; // 0-1, used when > 0
  final String? error;
  final List<ImportRow> rows;
  final _SortCol sortCol;
  final bool sortAsc;

  const _AnalysisState({
    this.status = _Status.idle,
    this.fetchMessage = '',
    this.fetchProgress = 0,
    this.error,
    this.rows = const [],
    this.sortCol = _SortCol.margin,
    this.sortAsc = false, // default: highest margin first
  });

  _AnalysisState copyWith({
    _Status? status,
    String? fetchMessage,
    double? fetchProgress,
    String? error,
    List<ImportRow>? rows,
    _SortCol? sortCol,
    bool? sortAsc,
  }) =>
      _AnalysisState(
        status: status ?? this.status,
        fetchMessage: fetchMessage ?? this.fetchMessage,
        fetchProgress: fetchProgress ?? this.fetchProgress,
        error: error ?? this.error,
        rows: rows ?? this.rows,
        sortCol: sortCol ?? this.sortCol,
        sortAsc: sortAsc ?? this.sortAsc,
      );

  List<ImportRow> get sortedRows {
    final sorted = List<ImportRow>.from(rows);
    sorted.sort((a, b) {
      final cmp = switch (sortCol) {
        _SortCol.name => a.typeName.compareTo(b.typeName),
        _SortCol.srcPrice => a.sourcePrice.compareTo(b.sourcePrice),
        _SortCol.dstPrice => a.destPrice.compareTo(b.destPrice),
        _SortCol.importPrice => a.importPrice.compareTo(b.importPrice),
        _SortCol.priceDiff => a.priceDiff.compareTo(b.priceDiff),
        _SortCol.margin => a.margin.compareTo(b.margin),
        _SortCol.srcOrders => a.sourceOrderCount.compareTo(b.sourceOrderCount),
        _SortCol.dstOrders => a.destOrderCount.compareTo(b.destOrderCount),
        _SortCol.dstVolume => a.destRemainingVolume.compareTo(b.destRemainingVolume),
        _SortCol.projectedProfit => a.projectedProfit.compareTo(b.projectedProfit),
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
      // New column: default sort direction
      final asc = col == _SortCol.name;
      state = current.copyWith(sortCol: col, sortAsc: asc);
    }
  }

  Future<void> analyze({
    required _Hub srcHub,
    required _Hub dstHub,
    required ImportParams params,
  }) async {
    final repo = ref.read(_regionOrdersRepoProvider);
    final sde = ref.read(sdeDatabaseProvider);

    state = state.copyWith(
      status: _Status.fetching,
      fetchMessage: 'Fetching ${srcHub.name} orders…',
      fetchProgress: 0,
      error: null,
    );

    try {
      // Fetch source region orders
      final srcOrders = await repo.fetchAllSellOrders(
        srcHub.regionId,
        onProgress: (done, total) {
          state = state.copyWith(
            fetchMessage:
                'Fetching ${srcHub.name} orders… ($done/$total pages)',
            fetchProgress: total > 0 ? done / (total * 2.0) : 0,
          );
        },
      );

      state = state.copyWith(
        fetchMessage: 'Fetching ${dstHub.name} orders…',
        fetchProgress: 0.5,
      );

      // Fetch destination region orders
      final dstOrders = await repo.fetchAllSellOrders(
        dstHub.regionId,
        onProgress: (done, total) {
          state = state.copyWith(
            fetchMessage:
                'Fetching ${dstHub.name} orders… ($done/$total pages)',
            fetchProgress: 0.5 + (total > 0 ? done / (total * 2.0) : 0),
          );
        },
      );

      state = state.copyWith(
        fetchMessage: 'Computing margins…',
        fetchProgress: 0.95,
      );

      // Lookup type names
      final allTypeIds = {
        ...srcOrders.map((o) => o.typeId),
        ...dstOrders.map((o) => o.typeId),
      }.toList();
      final typeInfo =
          sde != null ? sde.getTypesByIds(allTypeIds) : <int, InvType>{};

      final rows = ImportAnalysisComputer.compute(
        allSrcOrders: srcOrders,
        allDstOrders: dstOrders,
        srcStationId: srcHub.stationId,
        dstStationId: dstHub.stationId,
        typeInfo: typeInfo,
        params: params,
      );

      state = state.copyWith(
        status: _Status.ready,
        rows: rows,
        fetchProgress: 0,
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
// Import Analysis Tab
// ---------------------------------------------------------------------------

class ImportAnalysisTab extends ConsumerStatefulWidget {
  const ImportAnalysisTab({super.key});

  @override
  ConsumerState<ImportAnalysisTab> createState() =>
      _ImportAnalysisTabState();
}

class _ImportAnalysisTabState extends ConsumerState<ImportAnalysisTab> {
  _Hub _srcHub = _hubs[0]; // Jita
  _Hub _dstHub = _hubs[1]; // Amarr

  // Draft params
  double _pricePerM3 = 0;
  double _collateralPct = 0;
  double _minMarginPct = 2;
  PriceType _srcPriceType = PriceType.sell;
  PriceType _dstPriceType = PriceType.buy;
  bool _hideEmptySrcSell = false;

  void _analyze() {
    ref.read(_analysisProvider.notifier).analyze(
          srcHub: _srcHub,
          dstHub: _dstHub,
          params: ImportParams(
            srcPriceType: _srcPriceType,
            dstPriceType: _dstPriceType,
            pricePerM3: _pricePerM3,
            collateralPct: _collateralPct,
            minMarginPct: _minMarginPct,
            hideEmptySrcSell: _hideEmptySrcSell,
          ),
        );
  }

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final state = ref.watch(_analysisProvider);

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
              // Source hub
              Row(mainAxisSize: MainAxisSize.min, children: [
                Text('From:', style: theme.textTheme.bodySmall),
                const SizedBox(width: 6),
                _HubDropdown(
                  value: _srcHub,
                  onChanged: (h) => setState(() => _srcHub = h),
                ),
              ]),
              const Icon(Icons.arrow_forward, size: 16),
              // Destination hub
              Row(mainAxisSize: MainAxisSize.min, children: [
                Text('To:', style: theme.textTheme.bodySmall),
                const SizedBox(width: 6),
                _HubDropdown(
                  value: _dstHub,
                  onChanged: (h) => setState(() => _dstHub = h),
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
              const SizedBox(width: 8),
              // Logistics params
              _NumField(
                label: 'ISK/m³',
                value: _pricePerM3,
                onChanged: (v) => setState(() => _pricePerM3 = v),
              ),
              _NumField(
                label: 'Collateral %',
                value: _collateralPct,
                onChanged: (v) => setState(() => _collateralPct = v),
              ),
              _NumField(
                label: 'Min margin %',
                value: _minMarginPct,
                onChanged: (v) => setState(() => _minMarginPct = v),
              ),
              // Hide empty source sell checkbox
              Row(mainAxisSize: MainAxisSize.min, children: [
                Checkbox(
                  value: _hideEmptySrcSell,
                  onChanged: (v) =>
                      setState(() => _hideEmptySrcSell = v ?? false),
                ),
                Text('Hide empty src', style: theme.textTheme.bodySmall),
              ]),
              FilledButton.icon(
                onPressed:
                    state.status == _Status.fetching ? null : _analyze,
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
// Hub dropdown
// ---------------------------------------------------------------------------

class _HubDropdown extends StatelessWidget {
  final _Hub value;
  final void Function(_Hub) onChanged;

  const _HubDropdown({required this.value, required this.onChanged});

  @override
  Widget build(BuildContext context) {
    return DropdownButton<_Hub>(
      value: value,
      isDense: true,
      items: _hubs
          .map((h) => DropdownMenuItem(value: h, child: Text(h.name)))
          .toList(),
      onChanged: (h) {
        if (h != null) onChanged(h);
      },
    );
  }
}

// ---------------------------------------------------------------------------
// Price type selector (buy/sell dropdown)
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
// Numeric param field
// ---------------------------------------------------------------------------

class _NumField extends StatefulWidget {
  final String label;
  final double value;
  final void Function(double) onChanged;

  const _NumField(
      {required this.label, required this.value, required this.onChanged});

  @override
  State<_NumField> createState() => _NumFieldState();
}

class _NumFieldState extends State<_NumField> {
  late final TextEditingController _ctrl;

  @override
  void initState() {
    super.initState();
    _ctrl = TextEditingController(text: _fmt(widget.value));
  }

  @override
  void didUpdateWidget(_NumField old) {
    super.didUpdateWidget(old);
    if (old.value != widget.value) _ctrl.text = _fmt(widget.value);
  }

  @override
  void dispose() {
    _ctrl.dispose();
    super.dispose();
  }

  String _fmt(double v) =>
      v == v.truncateToDouble() ? v.toInt().toString() : v.toString();

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    return Row(
      mainAxisSize: MainAxisSize.min,
      children: [
        Text('${widget.label}:', style: theme.textTheme.bodySmall),
        const SizedBox(width: 4),
        SizedBox(
          width: 60,
          child: TextField(
            controller: _ctrl,
            inputFormatters: [
              FilteringTextInputFormatter.allow(RegExp(r'^\d*\.?\d*')),
            ],
            keyboardType: const TextInputType.numberWithOptions(decimal: true),
            decoration: const InputDecoration(
              isDense: true,
              border: OutlineInputBorder(),
              contentPadding:
                  EdgeInsets.symmetric(horizontal: 6, vertical: 4),
            ),
            style: theme.textTheme.bodySmall,
            onChanged: (text) {
              final v = double.tryParse(text);
              if (v != null && v >= 0) widget.onChanged(v);
            },
          ),
        ),
      ],
    );
  }
}

// ---------------------------------------------------------------------------
// Body (idle / fetching / ready / error)
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
              Icon(Icons.candlestick_chart_outlined,
                  size: 48, color: theme.colorScheme.onSurfaceVariant),
              const SizedBox(height: 16),
              Text(
                'Select trade hubs and press Analyse',
                style: theme.textTheme.bodyMedium?.copyWith(
                    color: theme.colorScheme.onSurfaceVariant),
              ),
            ],
          ),
        ),

      _Status.fetching => Center(
          child: SizedBox(
            width: 360,
            child: Column(
              mainAxisSize: MainAxisSize.min,
              children: [
                LinearProgressIndicator(
                  value: state.fetchProgress > 0
                      ? state.fetchProgress
                      : null,
                ),
                const SizedBox(height: 12),
                Text(
                  state.fetchMessage,
                  style: theme.textTheme.bodySmall?.copyWith(
                      color: theme.colorScheme.onSurfaceVariant),
                ),
              ],
            ),
          ),
        ),

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
          'No profitable opportunities found.\nTry lowering the minimum margin filter.',
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
          _HeaderCell('Name', _SortCol.name, flex: 4,
              sortCol: sortCol, sortAsc: sortAsc, onSort: onSort),
          _HeaderCell('Src Price', _SortCol.srcPrice, flex: 2,
              right: true, sortCol: sortCol, sortAsc: sortAsc, onSort: onSort),
          _HeaderCell('Src #', _SortCol.srcOrders, flex: 1,
              right: true, sortCol: sortCol, sortAsc: sortAsc, onSort: onSort),
          _HeaderCell('Import', _SortCol.importPrice, flex: 2,
              right: true, sortCol: sortCol, sortAsc: sortAsc, onSort: onSort),
          _HeaderCell('Dst Price', _SortCol.dstPrice, flex: 2,
              right: true, sortCol: sortCol, sortAsc: sortAsc, onSort: onSort),
          _HeaderCell('Dst #', _SortCol.dstOrders, flex: 1,
              right: true, sortCol: sortCol, sortAsc: sortAsc, onSort: onSort),
          _HeaderCell('Dst Vol', _SortCol.dstVolume, flex: 2,
              right: true, sortCol: sortCol, sortAsc: sortAsc, onSort: onSort),
          _HeaderCell('Diff/u', _SortCol.priceDiff, flex: 2,
              right: true, sortCol: sortCol, sortAsc: sortAsc, onSort: onSort),
          _HeaderCell('Proj. Profit', _SortCol.projectedProfit, flex: 2,
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
  final ImportRow row;
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
            flex: 4,
            child: Text(
              row.typeName,
              overflow: TextOverflow.ellipsis,
              style: theme.textTheme.bodySmall,
            ),
          ),
          // Source price
          Expanded(
            flex: 2,
            child: Text(
              _fmt(row.sourcePrice),
              textAlign: TextAlign.right,
              style: theme.textTheme.bodySmall,
            ),
          ),
          // Source order count
          Expanded(
            flex: 1,
            child: Text(
              row.sourceOrderCount.toString(),
              textAlign: TextAlign.right,
              style: theme.textTheme.bodySmall?.copyWith(
                  color: theme.colorScheme.onSurfaceVariant),
            ),
          ),
          // Import price
          Expanded(
            flex: 2,
            child: Text(
              _fmt(row.importPrice),
              textAlign: TextAlign.right,
              style: theme.textTheme.bodySmall?.copyWith(
                  color: theme.colorScheme.onSurfaceVariant),
            ),
          ),
          // Dest price
          Expanded(
            flex: 2,
            child: Text(
              _fmt(row.destPrice),
              textAlign: TextAlign.right,
              style: theme.textTheme.bodySmall,
            ),
          ),
          // Dest order count
          Expanded(
            flex: 1,
            child: Text(
              row.destOrderCount.toString(),
              textAlign: TextAlign.right,
              style: theme.textTheme.bodySmall?.copyWith(
                  color: theme.colorScheme.onSurfaceVariant),
            ),
          ),
          // Dest remaining volume
          Expanded(
            flex: 2,
            child: Text(
              _fmtVol(row.destRemainingVolume.toDouble()),
              textAlign: TextAlign.right,
              style: theme.textTheme.bodySmall?.copyWith(
                  color: theme.colorScheme.onSurfaceVariant),
            ),
          ),
          // Price diff (profit per unit)
          Expanded(
            flex: 2,
            child: Text(
              _fmt(row.priceDiff),
              textAlign: TextAlign.right,
              style: theme.textTheme.bodySmall
                  ?.copyWith(color: Colors.greenAccent),
            ),
          ),
          // Projected profit
          Expanded(
            flex: 2,
            child: Text(
              _fmt(row.projectedProfit),
              textAlign: TextAlign.right,
              style: theme.textTheme.bodySmall
                  ?.copyWith(color: Colors.greenAccent),
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
