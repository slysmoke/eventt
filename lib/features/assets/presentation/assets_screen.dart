import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../core/auth/auth_provider.dart';
import '../../../core/auth/active_character_provider.dart';
import '../../../core/auth/eve_auth_service.dart';
import '../../../core/database/database_provider.dart';
import '../../../core/esi/esi_provider.dart';
import '../data/asset_repository.dart';

final _assetsProvider = FutureProvider.autoDispose<List<Asset>>((ref) async {
  final characterAsync = ref.watch(activeCharacterProvider);
  final character = characterAsync.value;
  if (character == null) return [];

  final db = ref.watch(databaseProvider);
  final esi = ref.watch(esiClientProvider);
  final authService = ref.watch(eveAuthServiceProvider);

  try {
    final token = await authService.getValidAccessToken(character.id);
    final repo = AssetRepository(esi: esi, db: db);
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
                return _AssetsList(assets: assets);
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

class _AssetsList extends StatefulWidget {
  final List<Asset> assets;
  const _AssetsList({required this.assets});

  @override
  State<_AssetsList> createState() => _AssetsListState();
}

class _AssetsListState extends State<_AssetsList> {
  String _searchQuery = '';

  @override
  Widget build(BuildContext context) {
    final filtered = _searchQuery.isEmpty
        ? widget.assets
        : widget.assets
            .where((a) =>
                (a.typeName ?? '').toLowerCase().contains(_searchQuery.toLowerCase()) ||
                (a.locationName ?? '').toLowerCase().contains(_searchQuery.toLowerCase()))
            .toList();

    return Column(
      children: [
        // Search bar
        Padding(
          padding: const EdgeInsets.all(16),
          child: TextField(
            decoration: InputDecoration(
              hintText: 'Search by type or location...',
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
          child: Text(
            '${filtered.length} items',
            style: Theme.of(context).textTheme.labelMedium?.copyWith(
                  color: Theme.of(context).colorScheme.onSurfaceVariant,
                ),
          ),
        ),
        const Divider(height: 1),
        // Assets list
        Expanded(
          child: ListView.builder(
            itemCount: filtered.length,
            itemBuilder: (context, index) {
              final asset = filtered[index];
              return _AssetTile(asset: asset);
            },
          ),
        ),
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
    final locationName = asset.locationName ?? 'Location #${asset.locationId}';

    return ListTile(
      dense: true,
      contentPadding: const EdgeInsets.symmetric(horizontal: 16, vertical: 4),
      leading: CircleAvatar(
        backgroundColor: asset.isBlueprintCopy
            ? Colors.purple.withValues(alpha: 0.15)
            : theme.colorScheme.primaryContainer,
        child: Icon(
          asset.isBlueprintCopy ? Icons.copy : Icons.inventory_2,
          color: asset.isBlueprintCopy
              ? Colors.purple
              : theme.colorScheme.onPrimaryContainer,
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
                'Qty: ${asset.quantity}',
                style: theme.textTheme.labelSmall?.copyWith(
                  fontWeight: FontWeight.bold,
                  color: theme.colorScheme.primary,
                ),
              ),
              const SizedBox(width: 12),
              Expanded(
                child: Text(
                  locationName,
                  style: theme.textTheme.labelSmall?.copyWith(
                    color: theme.colorScheme.onSurfaceVariant,
                  ),
                  maxLines: 1,
                  overflow: TextOverflow.ellipsis,
                ),
              ),
            ],
          ),
        ],
      ),
      trailing: Text(
        'ID: ${asset.itemId}',
        style: theme.textTheme.labelSmall?.copyWith(
          color: theme.colorScheme.onSurfaceVariant,
          fontSize: 10,
        ),
      ),
    );
  }
}
