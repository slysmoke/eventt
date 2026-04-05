import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../core/auth/auth_provider.dart';
import '../../../core/database/database_provider.dart';
import '../../../core/database/app_database.dart';
import '../../auth/presentation/add_character_dialog.dart';

/// Shows the active character with a switcher, or an "Add Character" button.
class CharacterChip extends ConsumerWidget {
  const CharacterChip({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final activeId = ref.watch(activeCharacterIdProvider);
    final db = ref.watch(databaseProvider);

    return FutureBuilder(
      future: db.select(db.characters).get(),
      builder: (context, snapshot) {
        final characters = snapshot.data ?? [];

        if (characters.isEmpty) {
          return _AddButton(onTap: () => AddCharacterDialog.show(context));
        }

        final active = activeId != null
            ? characters.where((c) => c.id == activeId).firstOrNull
            : characters.first;

        return _CharacterTile(
          character: active ?? characters.first,
          hasMultiple: characters.length > 1,
          allCharacters: characters,
          onAddTap: () => AddCharacterDialog.show(context),
        );
      },
    );
  }
}

class _AddButton extends StatelessWidget {
  final VoidCallback onTap;
  const _AddButton({required this.onTap});

  @override
  Widget build(BuildContext context) {
    return OutlinedButton.icon(
      icon: const Icon(Icons.add, size: 16),
      label: const Text('Add Character'),
      style: OutlinedButton.styleFrom(
        padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 8),
        textStyle: Theme.of(context).textTheme.bodySmall,
      ),
      onPressed: onTap,
    );
  }
}

class _CharacterTile extends ConsumerWidget {
  final Character character;
  final bool hasMultiple;
  final List<Character> allCharacters;
  final VoidCallback onAddTap;

  const _CharacterTile({
    required this.character,
    required this.hasMultiple,
    required this.allCharacters,
    required this.onAddTap,
  });

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final theme = Theme.of(context);

    return InkWell(
      borderRadius: BorderRadius.circular(8),
      onTap: () => _showSwitcher(context, ref),
      child: Padding(
        padding: const EdgeInsets.symmetric(horizontal: 4, vertical: 6),
        child: Row(
          children: [
            _Portrait(characterId: character.id, url: character.portraitUrl),
            const SizedBox(width: 10),
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(
                    character.name,
                    style: theme.textTheme.bodySmall
                        ?.copyWith(fontWeight: FontWeight.w600),
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
                size: 16, color: theme.colorScheme.onSurfaceVariant),
          ],
        ),
      ),
    );
  }

  void _showSwitcher(BuildContext context, WidgetRef ref) {
    showDialog<void>(
      context: context,
      builder: (_) => _CharacterSwitcherDialog(
        characters: allCharacters,
        activeId: character.id,
        onSelect: (id) =>
            ref.read(activeCharacterIdProvider.notifier).select(id),
        onAdd: onAddTap,
      ),
    );
  }
}

class _Portrait extends StatelessWidget {
  final int characterId;
  final String? url;
  const _Portrait({required this.characterId, required this.url});

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    return CircleAvatar(
      radius: 16,
      backgroundImage: url != null ? NetworkImage(url!) : null,
      backgroundColor: theme.colorScheme.primaryContainer,
      child: url == null
          ? Icon(Icons.person_outline,
              size: 16, color: theme.colorScheme.onPrimaryContainer)
          : null,
    );
  }
}

class _CharacterSwitcherDialog extends StatelessWidget {
  final List<Character> characters;
  final int activeId;
  final void Function(int) onSelect;
  final VoidCallback onAdd;

  const _CharacterSwitcherDialog({
    required this.characters,
    required this.activeId,
    required this.onSelect,
    required this.onAdd,
  });

  @override
  Widget build(BuildContext context) {
    return AlertDialog(
      title: const Text('Switch Character'),
      contentPadding: const EdgeInsets.symmetric(vertical: 8),
      content: SizedBox(
        width: 300,
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            for (final char in characters)
              ListTile(
                leading: _Portrait(characterId: char.id, url: char.portraitUrl),
                title: Text(char.name),
                trailing: char.id == activeId
                    ? const Icon(Icons.check, size: 18)
                    : null,
                onTap: () {
                  onSelect(char.id);
                  Navigator.of(context).pop();
                },
              ),
            const Divider(height: 1),
            ListTile(
              leading: const Icon(Icons.add),
              title: const Text('Add another character'),
              onTap: () {
                Navigator.of(context).pop();
                onAdd();
              },
            ),
          ],
        ),
      ),
      actions: [
        TextButton(
          onPressed: () => Navigator.of(context).pop(),
          child: const Text('Close'),
        ),
      ],
    );
  }
}
