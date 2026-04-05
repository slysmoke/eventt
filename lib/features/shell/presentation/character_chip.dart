import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../core/auth/auth_provider.dart';
import '../../../core/database/database_provider.dart';

/// Shows the active character or an "Add Character" button.
class CharacterChip extends ConsumerWidget {
  const CharacterChip({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final activeId = ref.watch(activeCharacterIdProvider);
    final theme = Theme.of(context);

    if (activeId == null) {
      return OutlinedButton.icon(
        icon: const Icon(Icons.add, size: 16),
        label: const Text('Add Character'),
        style: OutlinedButton.styleFrom(
          padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 8),
          textStyle: theme.textTheme.bodySmall,
        ),
        onPressed: () {
          // TODO: trigger OAuth flow
        },
      );
    }

    return _CharacterTile(characterId: activeId);
  }
}

class _CharacterTile extends ConsumerWidget {
  final int characterId;
  const _CharacterTile({required this.characterId});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final db = ref.watch(databaseProvider);
    final theme = Theme.of(context);

    return FutureBuilder(
      future: (db.select(db.characters)
            ..where((t) => t.id.equals(characterId)))
          .getSingleOrNull(),
      builder: (context, snapshot) {
        final character = snapshot.data;
        final name = character?.name ?? 'Loading…';
        final portrait = character?.portraitUrl;

        return InkWell(
          borderRadius: BorderRadius.circular(8),
          onTap: () {
            // TODO: character switcher
          },
          child: Padding(
            padding: const EdgeInsets.symmetric(horizontal: 4, vertical: 6),
            child: Row(
              children: [
                CircleAvatar(
                  radius: 16,
                  backgroundImage:
                      portrait != null ? NetworkImage(portrait) : null,
                  backgroundColor: theme.colorScheme.primaryContainer,
                  child: portrait == null
                      ? Icon(Icons.person_outline,
                          size: 16,
                          color: theme.colorScheme.onPrimaryContainer)
                      : null,
                ),
                const SizedBox(width: 10),
                Expanded(
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Text(
                        name,
                        style: theme.textTheme.bodySmall?.copyWith(
                          fontWeight: FontWeight.w600,
                        ),
                        overflow: TextOverflow.ellipsis,
                      ),
                      Text(
                        'Active character',
                        style: theme.textTheme.labelSmall?.copyWith(
                          color: theme.colorScheme.onSurfaceVariant,
                        ),
                      ),
                    ],
                  ),
                ),
                Icon(Icons.unfold_more,
                    size: 16,
                    color: theme.colorScheme.onSurfaceVariant),
              ],
            ),
          ),
        );
      },
    );
  }
}
