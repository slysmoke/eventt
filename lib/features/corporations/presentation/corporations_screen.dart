import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../core/database/database_provider.dart';
import '../../../core/database/app_database.dart';

final _corporationsStreamProvider = StreamProvider<List<Corporation>>((ref) {
  final db = ref.watch(databaseProvider);
  return db.select(db.corporations).watch();
});

class CorporationsScreen extends ConsumerWidget {
  const CorporationsScreen({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final corpsAsync = ref.watch(_corporationsStreamProvider);

    return Scaffold(
      appBar: AppBar(
        title: const Text('Corporations'),
        actions: [
          IconButton(
            icon: const Icon(Icons.refresh),
            tooltip: 'Refresh',
            onPressed: () => ref.invalidate(_corporationsStreamProvider),
          ),
        ],
      ),
      body: corpsAsync.when(
        loading: () => const Center(child: CircularProgressIndicator()),
        error: (e, _) => Center(
          child: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              const Icon(Icons.error_outline, size: 48, color: Colors.red),
              const SizedBox(height: 8),
              Text('$e'),
            ],
          ),
        ),
        data: (corporations) {
          if (corporations.isEmpty) {
            return Center(
              child: Column(
                mainAxisSize: MainAxisSize.min,
                children: [
                  Icon(Icons.business,
                      size: 64,
                      color: Theme.of(context)
                          .colorScheme
                          .onSurface
                          .withValues(alpha: 0.15)),
                  const SizedBox(height: 16),
                  Text(
                    'No corporations yet',
                    style: Theme.of(context).textTheme.titleMedium?.copyWith(
                          color: Theme.of(context)
                              .colorScheme
                              .onSurface
                              .withValues(alpha: 0.4),
                        ),
                  ),
                  const SizedBox(height: 8),
                  Text(
                    'Add a character via OAuth to automatically\nimport their corporation.',
                    textAlign: TextAlign.center,
                    style: Theme.of(context).textTheme.bodyMedium?.copyWith(
                          color: Theme.of(context)
                              .colorScheme
                              .onSurface
                              .withValues(alpha: 0.4),
                        ),
                  ),
                ],
              ),
            );
          }

          return ListView.builder(
            itemCount: corporations.length,
            itemBuilder: (context, index) {
              final corp = corporations[index];
              return _CorporationTile(corporation: corp);
            },
          );
        },
      ),
    );
  }
}

class _CorporationTile extends StatelessWidget {
  final Corporation corporation;
  const _CorporationTile({required this.corporation});

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);

    return ListTile(
      leading: CircleAvatar(
        backgroundColor: theme.colorScheme.primaryContainer,
        child: Icon(Icons.business,
            color: theme.colorScheme.onPrimaryContainer, size: 20),
      ),
      title: Text(
        corporation.name,
        style: theme.textTheme.bodyLarge?.copyWith(fontWeight: FontWeight.w600),
      ),
      subtitle: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          if (corporation.ticker != null)
            Text(
              '[${corporation.ticker}]',
              style: theme.textTheme.labelSmall?.copyWith(
                color: theme.colorScheme.primary,
                fontWeight: FontWeight.bold,
              ),
            ),
          if (corporation.allianceName != null)
            Text(
              'Alliance: ${corporation.allianceName}',
              style: theme.textTheme.labelSmall?.copyWith(
                color: theme.colorScheme.onSurfaceVariant,
              ),
            ),
          if (corporation.ceoName != null)
            Text(
              'CEO: ${corporation.ceoName}',
              style: theme.textTheme.labelSmall?.copyWith(
                color: theme.colorScheme.onSurfaceVariant,
              ),
            ),
        ],
      ),
      trailing: Text(
        'ID: ${corporation.id}',
        style: theme.textTheme.labelSmall?.copyWith(
          color: theme.colorScheme.onSurfaceVariant,
        ),
      ),
    );
  }
}
