import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../core/esi/esi_provider.dart';
import '../../../core/sde/sde_models.dart';
import '../../../core/sde/sde_provider.dart';
import '../../market_browser/data/market_order_repository.dart';
import '../domain/import_params.dart';
import '../domain/ore_reprocessing_computer.dart';
import '../domain/ore_reprocessing_row.dart';

// ---------------------------------------------------------------------------
// Providers
// ---------------------------------------------------------------------------

final _regionOrdersRepoProvider = Provider<MarketOrderRepository>((ref) {
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
  final List<OreReprocessingRow> rows;
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
    List<OreReprocessingRow>? rows,
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

  List<OreReprocessingRow> get sortedRows {
    final sorted = List<OreReprocessingRow>.from(rows);
    sorted.sort((a, b) {
      final cmp = switch (sortCol) {
        _SortCol.name => a.oreName.compareTo(b.oreName),
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
    required OreReprocessingParams params,
  }) async {
    final repo = ref.read(_regionOrdersRepoProvider);
    final sde = ref.read(sdeDatabaseProvider);

    state = state.copyWith(
      status: _Status.fetching,
      error: null,
    );

    try {
      // Fetch all orders in the region
      final allTypeIds = sde?.getAllPublishedTypeIds() ?? [];
      final allOrders = <MarketOrder>[];

      for (var i = 0; i < allTypeIds.length; i += 100) {
        final batch = allTypeIds.skip(i).take(100);
        final futures = batch.map((typeId) =>
            repo.fetchOrders(regionId, typeId));
        final results = await Future.wait(futures);
        for (final orders in results) {
          allOrders.addAll(orders);
        }
      }

      // Build ore info from SDE
      // For simplicity, we use a hardcoded list of common ores
      // In production, this would come from SDE industryActivity tables
      final oreInfo = _buildOreInfo(sde);

      // Lookup type names
      final allIds = allOrders.map((o) => o.typeId).toSet().toList();
      final typeInfo = sde != null
          ? sde.getTypesByIds(allIds)
          : <int, InvType>{};

      final rows = OreReprocessingComputer.compute(
        srcOrders: allOrders,
        dstOrders: allOrders, // same region for simplicity
        oreInfo: oreInfo,
        typeInfo: typeInfo,
        params: params,
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

  /// Build ore reprocessing info.
  /// In production, this comes from SDE industryActivityMaterials table.
  Map<int, OreReprocessingInfo> _buildOreInfo(dynamic sde) {
    // Simplified hardcoded ore data
    return {
      1230: const OreReprocessingInfo( // Veldspar
        oreTypeId: 1230,
        portionSize: 333,
        materials: [
          ReprocessedMaterial(typeId: 34, quantity: 408), // Tritanium
          ReprocessedMaterial(typeId: 35, quantity: 170), // Pyerite
        ],
      ),
      1228: const OreReprocessingInfo( // Scordite
        oreTypeId: 1228,
        portionSize: 333,
        materials: [
          ReprocessedMaterial(typeId: 34, quantity: 483),
          ReprocessedMaterial(typeId: 36, quantity: 217), // Mexallon
        ],
      ),
      1232: const OreReprocessingInfo( // Pyroxeres
        oreTypeId: 1232,
        portionSize: 333,
        materials: [
          ReprocessedMaterial(typeId: 34, quantity: 338),
          ReprocessedMaterial(typeId: 35, quantity: 149),
          ReprocessedMaterial(typeId: 37, quantity: 16),  // Isogen
        ],
      ),
      1226: const OreReprocessingInfo( // Plagioclase
        oreTypeId: 1226,
        portionSize: 333,
        materials: [
          ReprocessedMaterial(typeId: 34, quantity: 467),
          ReprocessedMaterial(typeId: 36, quantity: 200),
        ],
      ),
      1224: const OreReprocessingInfo( // Omber
        oreTypeId: 1224,
        portionSize: 333,
        materials: [
          ReprocessedMaterial(typeId: 34, quantity: 333),
          ReprocessedMaterial(typeId: 37, quantity: 16),
        ],
      ),
    };
  }
}

final _analysisProvider =
    NotifierProvider<_AnalysisNotifier, _AnalysisState>(_AnalysisNotifier.new);

// ---------------------------------------------------------------------------
// Ore Reprocessing Tab Widget
// ---------------------------------------------------------------------------

class OreReprocessingTab extends ConsumerStatefulWidget {
  const OreReprocessingTab({super.key});

  @override
  ConsumerState<OreReprocessingTab> createState() =>
      _OreReprocessingTabState();
}

class _OreReprocessingTabState extends ConsumerState<OreReprocessingTab> {
  int? _selectedRegionId;
  double _stationEfficiency = 0.5;
  int _reprocessingSkill = 0;
  int _reprocessingEfficiency = 0;
  PriceType _dstPriceType = PriceType.buy;
  bool _useStationTax = false;

  void _analyze() {
    if (_selectedRegionId == null) return;
    ref.read(_analysisProvider.notifier).analyze(
          regionId: _selectedRegionId!,
          params: OreReprocessingParams(
            stationEfficiency: _stationEfficiency,
            reprocessingSkillLevel: _reprocessingSkill,
            reprocessingEfficiencyLevel: _reprocessingEfficiency,
            dstPriceType: _dstPriceType,
            useStationTax: _useStationTax,
            stationTax: 0.05, // default 5%
          ),
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
              // Region selector
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
              const SizedBox(width: 8),
              // Station efficiency
              Row(mainAxisSize: MainAxisSize.min, children: [
                Text('Efficiency:', style: theme.textTheme.bodySmall),
                const SizedBox(width: 4),
                SizedBox(
                  width: 60,
                  child: TextField(
                    decoration: const InputDecoration(
                      isDense: true,
                      border: OutlineInputBorder(),
                      contentPadding:
                          EdgeInsets.symmetric(horizontal: 6, vertical: 4),
                    ),
                    style: theme.textTheme.bodySmall,
                    keyboardType: const TextInputType.numberWithOptions(decimal: true),
                    controller: TextEditingController(
                        text: (_stationEfficiency * 100).toStringAsFixed(0)),
                    onChanged: (text) {
                      final v = double.tryParse(text);
                      if (v != null && v >= 0 && v <= 100) {
                        setState(() => _stationEfficiency = v / 100);
                      }
                    },
                  ),
                ),
                Text('%', style: theme.textTheme.bodySmall),
              ]),
              // Reprocessing skill
              Row(mainAxisSize: MainAxisSize.min, children: [
                Text('Reproc:', style: theme.textTheme.bodySmall),
                const SizedBox(width: 4),
                DropdownButton<int>(
                  value: _reprocessingSkill,
                  isDense: true,
                  items: List.generate(6, (i) => i)
                      .map((i) => DropdownMenuItem(
                            value: i,
                            child: Text('$i'),
                          ))
                      .toList(),
                  onChanged: (v) => setState(() => _reprocessingSkill = v ?? 0),
                ),
              ]),
              // Efficiency skill
              Row(mainAxisSize: MainAxisSize.min, children: [
                Text('Eff:', style: theme.textTheme.bodySmall),
                const SizedBox(width: 4),
                DropdownButton<int>(
                  value: _reprocessingEfficiency,
                  isDense: true,
                  items: List.generate(6, (i) => i)
                      .map((i) => DropdownMenuItem(
                            value: i,
                            child: Text('$i'),
                          ))
                      .toList(),
                  onChanged: (v) =>
                      setState(() => _reprocessingEfficiency = v ?? 0),
                ),
              ]),
              // Destination price type
              _PriceTypeSelector(
                label: 'Dst',
                value: _dstPriceType,
                onChanged: (v) => setState(() => _dstPriceType = v),
              ),
              // Station tax checkbox
              Row(mainAxisSize: MainAxisSize.min, children: [
                Checkbox(
                  value: _useStationTax,
                  onChanged: (v) =>
                      setState(() => _useStationTax = v ?? false),
                ),
                Text('Tax', style: theme.textTheme.bodySmall),
              ]),
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
              Icon(Icons.diamond,
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
          'No profitable reprocessing opportunities found.',
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
            '${rows.length} ores',
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
          _HeaderCell('Volume', _SortCol.volume, flex: 2,
              right: true, sortCol: sortCol, sortAsc: sortAsc, onSort: onSort),
          _HeaderCell('Total Profit', _SortCol.totalProfit, flex: 2,
              right: true, sortCol: sortCol, sortAsc: sortAsc, onSort: onSort),
          _HeaderCell('Total Cost', _SortCol.totalCost, flex: 2,
              right: true, sortCol: sortCol, sortAsc: sortAsc, onSort: onSort),
          _HeaderCell('Difference', _SortCol.difference, flex: 2,
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
  final OreReprocessingRow row;
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
              row.oreName,
              overflow: TextOverflow.ellipsis,
              style: theme.textTheme.bodySmall,
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
          // Total profit
          Expanded(
            flex: 2,
            child: Text(
              _fmt(row.totalProfit),
              textAlign: TextAlign.right,
              style: theme.textTheme.bodySmall
                  ?.copyWith(color: Colors.greenAccent),
            ),
          ),
          // Total cost
          Expanded(
            flex: 2,
            child: Text(
              _fmt(row.totalCost),
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
