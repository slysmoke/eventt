import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import 'updater_provider.dart';

/// Banner shown at the top of the app when an update is available.
class UpdateBanner extends ConsumerWidget {
  const UpdateBanner({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final state = ref.watch(updateCheckProvider);

    // Only show when there's an update info and not downloading
    if (state.updateInfo == null || state.isDownloading) {
      return const SizedBox.shrink();
    }

    final info = state.updateInfo!;
    final theme = Theme.of(context);

    return Material(
      color: theme.colorScheme.primaryContainer,
      child: Padding(
        padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 8),
        child: Row(
          children: [
            Icon(Icons.system_update, color: theme.colorScheme.onPrimaryContainer),
            const SizedBox(width: 12),
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(
                    'Update ${info.latestVersion} available',
                    style: theme.textTheme.bodySmall?.copyWith(
                      color: theme.colorScheme.onPrimaryContainer,
                      fontWeight: FontWeight.bold,
                    ),
                  ),
                  Text(
                    'You are on ${info.currentVersion}',
                    style: theme.textTheme.labelSmall?.copyWith(
                      color: theme.colorScheme.onPrimaryContainer.withValues(alpha: 0.7),
                    ),
                  ),
                ],
              ),
            ),
            const SizedBox(width: 8),
            // Download button
            FilledButton.tonal(
              onPressed: () => _showUpdateDialog(context, ref),
              child: const Text('Update'),
            ),
            const SizedBox(width: 4),
            // Dismiss
            IconButton(
              icon: const Icon(Icons.close, size: 18),
              onPressed: () => ref.read(updateCheckProvider.notifier).dismiss(),
            ),
          ],
        ),
      ),
    );
  }

  void _showUpdateDialog(BuildContext context, WidgetRef ref) {
    showDialog<void>(
      context: context,
      builder: (_) => _UpdateDialog(),
    );
  }
}

class _UpdateDialog extends ConsumerWidget {
  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final state = ref.watch(updateCheckProvider);
    final info = state.updateInfo;
    if (info == null) return const SizedBox.shrink();

    final theme = Theme.of(context);

    return AlertDialog(
      title: Row(
        children: [
          Icon(Icons.system_update, color: theme.colorScheme.primary),
          const SizedBox(width: 8),
          Text('Update ${info.latestVersion}'),
        ],
      ),
      content: SizedBox(
        width: 400,
        child: SingleChildScrollView(
          child: Column(
            mainAxisSize: MainAxisSize.min,
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Text(
                'You are currently on version ${info.currentVersion}.',
                style: theme.textTheme.bodyMedium,
              ),
              const SizedBox(height: 12),
              Text(
                'Release notes:',
                style: theme.textTheme.labelLarge?.copyWith(
                  fontWeight: FontWeight.bold,
                ),
              ),
              const SizedBox(height: 4),
              Text(
                info.releaseNotes,
                style: theme.textTheme.bodySmall,
              ),
              if (state.isDownloading) ...[
                const SizedBox(height: 16),
                LinearProgressIndicator(value: state.downloadProgress),
                const SizedBox(height: 4),
                Text(
                  'Downloading... ${(state.downloadProgress * 100).toStringAsFixed(0)}%',
                  style: theme.textTheme.labelSmall,
                ),
              ],
              if (state.downloadedPath != null) ...[
                const SizedBox(height: 16),
                Text(
                  'Download complete! The update will be installed now.',
                  style: theme.textTheme.bodySmall?.copyWith(
                    color: theme.colorScheme.primary,
                  ),
                ),
              ],
              if (state.error != null) ...[
                const SizedBox(height: 16),
                Text(
                  'Error: ${state.error}',
                  style: theme.textTheme.bodySmall?.copyWith(
                    color: theme.colorScheme.error,
                  ),
                ),
              ],
            ],
          ),
        ),
      ),
      actions: [
        TextButton(
          onPressed: () {
            ref.read(updateCheckProvider.notifier).dismiss();
            Navigator.of(context).pop();
          },
          child: const Text('Later'),
        ),
        if (state.downloadedPath != null)
          FilledButton(
            onPressed: () async {
              final success = await ref.read(updateCheckProvider.notifier).install();
              if (!context.mounted) return;
              if (!success) {
                // Fallback: open release page
                await ref.read(updateCheckProvider.notifier).openReleasePage();
              }
              if (context.mounted) Navigator.of(context).pop();
            },
            child: const Text('Install'),
          )
        else if (!state.isDownloading)
          FilledButton(
            onPressed: () => ref.read(updateCheckProvider.notifier).download(),
            child: const Text('Download'),
          ),
      ],
    );
  }
}
