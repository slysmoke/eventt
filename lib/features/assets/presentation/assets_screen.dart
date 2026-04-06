import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../core/auth/auth_provider.dart';
import '../../../core/auth/active_character_provider.dart';
import '../../../core/auth/eve_auth_service.dart';
import '../../../core/esi/esi_provider.dart';
import '../../../core/sde/sde_provider.dart';
import '../data/asset_repository.dart';

final _assetsProvider = FutureProvider.autoDispose<List<Asset>>((ref) async {
  final characterAsync = ref.watch(activeCharacterProvider);
  final character = characterAsync.value;
  if (character == null) return [];

  final esi = ref.watch(esiClientProvider);
  final sde = ref.watch(sdeDatabaseProvider);
  final authService = ref.watch(eveAuthServiceProvider);

  try {
    final token = await authService.getValidAccessToken(character.id);
    final repo = AssetRepository(esi: esi, sde: sde);
    return repo.fetchAssets(characterId: character.id, accessToken: token);
  } catch (_) {
    return [];
  }
});

class AssetsScreen extends ConsumerWidget {
  const AssetsScreen({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final assetsAsync = ref.watch(_assetsProvider);
    final characterAsync = ref.watch(activeCharacterProvider);
    final character = characterAsync.value;

    return Scaffold(
      appBar: AppBar(
        title: const Text('Assets'),
        actions: [
          IconButton(
            icon: const Icon(Icons.refresh),
            tooltip: 'Refresh',
            onPressed: () => ref.invalidate(_assetsProvider),
          ),
        ],
      ),
      body: character == null
          ? const _NoCharacterView()
          : assetsAsync.when(
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
                      onPressed: () => ref.invalidate(_assetsProvider),
                      child: const Text('Retry'),
                    ),
                  ],
                ),
              ),
              data: (assets) {
                if (assets.isEmpty) {
                  return const _EmptyAssetsView();
                }
                return _GroupedAssetsView(assets: assets);
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
            'Add a character via OAuth to see their assets.',
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

class _EmptyAssetsView extends StatelessWidget {
  const _EmptyAssetsView();

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    return Center(
      child: Column(
        mainAxisSize: MainAxisSize.min,
        children: [
          Icon(Icons.inventory_2,
              size: 64,
              color: theme.colorScheme.onSurface.withValues(alpha: 0.15)),
          const SizedBox(height: 16),
          Text(
            'No assets found',
            style: theme.textTheme.titleMedium?.copyWith(
              color: theme.colorScheme.onSurface.withValues(alpha: 0.4),
            ),
          ),
        ],
      ),
    );
  }
}

/// Grouped assets by Region → System → Station.
class _GroupedAssetsView extends StatefulWidget {
  final List<Asset> assets;
  const _GroupedAssetsView({required this.assets});

  @override
  State<_GroupedAssetsView> createState() => _GroupedAssetsViewState();
}

class _GroupedAssetsViewState extends State<_GroupedAssetsView> {
  final Map<String, bool> _expandedRegions = {};
  final Map<String, bool> _expandedSystems = {};
  String _searchQuery = '';

  @override
  Widget build(BuildContext context) {
    // Group assets
    final regions = <String, Map<String, Map<String, List<Asset>>>>{};
    final regionTotals = <String, double>{};

    for (final asset in widget.assets) {
      final regionName = asset.regionName ?? 'Unknown Region';
      final systemName = asset.systemName ?? 'Unknown System';
      final stationName = asset.stationName ?? 'Unknown Station';

      regions.putIfAbsent(regionName, () => {});
      regions[regionName]!.putIfAbsent(systemName, () => {});
      regions[regionName]![systemName]!.putIfAbsent(stationName, () => []);
      regions[regionName]![systemName]![stationName]!.add(asset);

      regionTotals[regionName] = (regionTotals[regionName] ?? 0) + asset.totalPrice;
    }

    // Filter by search
    final filteredRegions = <String, Map<String, Map<String, List<Asset>>>>{};
    for (final region in regions.entries) {
      final filteredSystems = <String, Map<String, List<Asset>>>{};
      for (final system in region.value.entries) {
        final filteredStations = <String, List<Asset>>{};
        for (final station in system.value.entries) {
          final matchesSearch = _searchQuery.isEmpty ||
              station.value.any((a) =>
                  (a.typeName ?? '').toLowerCase().contains(_searchQuery.toLowerCase()) ||
                  station.key.toLowerCase().contains(_searchQuery.toLowerCase()));
          if (matchesSearch) {
            filteredStations[station.key] = station.value;
          }
        }
        if (filteredStations.isNotEmpty) {
          filteredSystems[system.key] = filteredStations;
        }
      }
      if (filteredSystems.isNotEmpty) {
        filteredRegions[region.key] = filteredSystems;
      }
    }

    return Column(
      children: [
        // Search bar
        Padding(
          padding: const EdgeInsets.all(16),
          child: TextField(
            decoration: InputDecoration(
              hintText: 'Search by type or station...',
              prefixIcon: const Icon(Icons.search, size: 20),
              suffixIcon: _searchQuery.isNotEmpty
                  ? IconButton(
                      icon: const Icon(Icons.clear, size: 20),
                      onPressed: () => setState(() => _searchQuery = ''),
                    )
                  : null,
              border: OutlineInputBorder(
                borderRadius: BorderRadius.circular(8),
              ),
              isDense: true,
            ),
            onChanged: (value) => setState(() => _searchQuery = value),
          ),
        ),
        // Summary
        Padding(
          padding: const EdgeInsets.symmetric(horizontal: 16),
          child: Row(
            children: [
              Text(
                '${widget.assets.length} items',
                style: Theme.of(context).textTheme.labelMedium?.copyWith(
                      color: Theme.of(context).colorScheme.onSurfaceVariant,
                    ),
              ),
              const Spacer(),
              Text(
                'Total: ${_fmtIsk(regionTotals.values.fold<double>(0, (a, b) => a + b))} ISK',
                style: Theme.of(context).textTheme.labelMedium?.copyWith(
                      color: Theme.of(context).colorScheme.primary,
                      fontWeight: FontWeight.bold,
                    ),
              ),
            ],
          ),
        ),
        const Divider(height: 1),
        // Grouped list
        Expanded(
          child: ListView.builder(
            itemCount: filteredRegions.length,
            itemBuilder: (context, index) {
              final regionName = filteredRegions.keys.elementAt(index);
              final systems = filteredRegions[regionName]!;
              return _RegionGroup(
                regionName: regionName,
                totalValue: regionTotals[regionName] ?? 0,
                systems: systems,
                expanded: _expandedRegions[regionName] ?? true,
                onToggle: () => setState(
                  () => _expandedRegions[regionName] = !(_expandedRegions[regionName] ?? true),
                ),
                expandedSystems: _expandedSystems,
                onToggleSystem: (system) => setState(
                  () => _expandedSystems[system] = !(_expandedSystems[system] ?? true),
                ),
              );
            },
          ),
        ),
      ],
    );
  }
}

class _RegionGroup extends StatelessWidget {
  final String regionName;
  final double totalValue;
  final Map<String, Map<String, List<Asset>>> systems;
  final bool expanded;
  final VoidCallback onToggle;
  final Map<String, bool> expandedSystems;
  final void Function(String) onToggleSystem;

  const _RegionGroup({
    required this.regionName,
    required this.totalValue,
    required this.systems,
    required this.expanded,
    required this.onToggle,
    required this.expandedSystems,
    required this.onToggleSystem,
  });

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);

    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        // Region header
        InkWell(
          onTap: onToggle,
          child: Container(
            padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 12),
            color: theme.colorScheme.primaryContainer.withValues(alpha: 0.3),
            child: Row(
              children: [
                Icon(
                  expanded ? Icons.expand_more : Icons.chevron_right,
                  size: 20,
                  color: theme.colorScheme.primary,
                ),
                const SizedBox(width: 8),
                Icon(Icons.public, size: 18, color: theme.colorScheme.primary),
                const SizedBox(width: 8),
                Expanded(
                  child: Text(
                    regionName,
                    style: theme.textTheme.titleSmall?.copyWith(
                      color: theme.colorScheme.primary,
                      fontWeight: FontWeight.bold,
                    ),
                  ),
                ),
                Text(
                  _fmtIsk(totalValue),
                  style: theme.textTheme.labelMedium?.copyWith(
                    color: theme.colorScheme.primary,
                    fontWeight: FontWeight.bold,
                  ),
                ),
              ],
            ),
          ),
        ),
        if (expanded) ...[
          for (final system in systems.entries)
            _SystemGroup(
              systemName: system.key,
              stations: system.value,
              expanded: expandedSystems[system.key] ?? true,
              onToggle: () => onToggleSystem(system.key),
            ),
        ],
        const Divider(height: 1),
      ],
    );
  }
}

class _SystemGroup extends StatelessWidget {
  final String systemName;
  final Map<String, List<Asset>> stations;
  final bool expanded;
  final VoidCallback onToggle;

  const _SystemGroup({
    required this.systemName,
    required this.stations,
    required this.expanded,
    required this.onToggle,
  });

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final systemTotal = stations.values.expand((a) => a).fold<double>(
          0,
          (sum, asset) => sum + asset.totalPrice,
        );

    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        // System header
        InkWell(
          onTap: onToggle,
          child: Container(
            padding: const EdgeInsets.symmetric(horizontal: 32, vertical: 8),
            color: theme.colorScheme.surfaceContainerHighest.withValues(alpha: 0.3),
            child: Row(
              children: [
                Icon(
                  expanded ? Icons.expand_more : Icons.chevron_right,
                  size: 16,
                  color: theme.colorScheme.onSurfaceVariant,
                ),
                const SizedBox(width: 6),
                Icon(Icons.star, size: 14, color: theme.colorScheme.onSurfaceVariant),
                const SizedBox(width: 6),
                Expanded(
                  child: Text(
                    systemName,
                    style: theme.textTheme.bodySmall?.copyWith(
                      color: theme.colorScheme.onSurfaceVariant,
                      fontWeight: FontWeight.w600,
                    ),
                  ),
                ),
                Text(
                  _fmtIsk(systemTotal),
                  style: theme.textTheme.labelSmall?.copyWith(
                    color: theme.colorScheme.onSurfaceVariant,
                  ),
                ),
              ],
            ),
          ),
        ),
        if (expanded)
          for (final station in stations.entries)
            _StationGroup(
              stationName: station.key,
              assets: station.value,
            ),
      ],
    );
  }
}

class _StationGroup extends StatelessWidget {
  final String stationName;
  final List<Asset> assets;

  const _StationGroup({
    required this.stationName,
    required this.assets,
  });

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final stationTotal = assets.fold<double>(0, (sum, a) => sum + a.totalPrice);

    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        // Station header
        Container(
          padding: const EdgeInsets.symmetric(horizontal: 48, vertical: 6),
          color: theme.colorScheme.surfaceContainerHighest.withValues(alpha: 0.15),
          child: Row(
            children: [
              Icon(Icons.location_on, size: 14, color: theme.colorScheme.tertiary),
              const SizedBox(width: 6),
              Expanded(
                child: Text(
                  stationName,
                  style: theme.textTheme.bodySmall?.copyWith(
                    color: theme.colorScheme.tertiary,
                    fontWeight: FontWeight.w500,
                  ),
                ),
              ),
              Text(
                _fmtIsk(stationTotal),
                style: theme.textTheme.labelSmall?.copyWith(
                  color: theme.colorScheme.tertiary,
                ),
              ),
            ],
          ),
        ),
        // Asset items
        for (final asset in assets)
          _AssetTile(asset: asset),
      ],
    );
  }
}

class _AssetTile extends StatelessWidget {
  final Asset asset;
  const _AssetTile({required this.asset});

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final typeName = asset.typeName ?? 'Type #${asset.typeId}';

    return Padding(
      padding: const EdgeInsets.symmetric(horizontal: 48, vertical: 2),
      child: Row(
        children: [
          Icon(
            asset.isBlueprintCopy ? Icons.copy : Icons.inventory_2,
            size: 14,
            color: asset.isBlueprintCopy
                ? Colors.purple
                : theme.colorScheme.onSurfaceVariant,
          ),
          const SizedBox(width: 8),
          Expanded(
            child: Text(
              typeName,
              style: theme.textTheme.bodySmall,
              maxLines: 1,
              overflow: TextOverflow.ellipsis,
            ),
          ),
          const SizedBox(width: 8),
          Text(
            '×${asset.quantity}',
            style: theme.textTheme.labelSmall?.copyWith(
              color: theme.colorScheme.onSurfaceVariant,
            ),
          ),
          const SizedBox(width: 12),
          SizedBox(
            width: 80,
            child: Text(
              asset.unitPrice != null ? '${_fmtIsk(asset.unitPrice!)}' : '—',
              style: theme.textTheme.labelSmall?.copyWith(
                color: theme.colorScheme.onSurfaceVariant,
              ),
              textAlign: TextAlign.right,
            ),
          ),
          const SizedBox(width: 8),
          SizedBox(
            width: 80,
            child: Text(
              _fmtIsk(asset.totalPrice),
              style: theme.textTheme.labelSmall?.copyWith(
                fontWeight: FontWeight.bold,
                color: theme.colorScheme.primary,
              ),
              textAlign: TextAlign.right,
            ),
          ),
        ],
      ),
    );
  }
}

String _fmtIsk(double v) {
  final abs = v.abs();
  if (abs >= 1e12) return '${(v / 1e12).toStringAsFixed(2)}T';
  if (abs >= 1e9) return '${(v / 1e9).toStringAsFixed(2)}B';
  if (abs >= 1e6) return '${(v / 1e6).toStringAsFixed(2)}M';
  if (abs >= 1e3) return '${(v / 1e3).toStringAsFixed(2)}K';
  return v.toStringAsFixed(2);
}
