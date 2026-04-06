import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import 'app_route.dart';
import 'character_chip.dart';

const _sidebarWidth = 220.0;

class Sidebar extends ConsumerWidget {
  const Sidebar({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final current = ref.watch(currentRouteProvider);
    final theme = Theme.of(context);

    return SizedBox(
      width: _sidebarWidth,
      child: Material(
        color: theme.colorScheme.surface,
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            // App title
            Padding(
              padding: const EdgeInsets.fromLTRB(16, 20, 16, 8),
              child: Text(
                'EVE NTT',
                style: theme.textTheme.titleLarge?.copyWith(
                  color: theme.colorScheme.primary,
                  fontWeight: FontWeight.bold,
                  letterSpacing: 1.5,
                ),
              ),
            ),

            // Character chip
            const Padding(
              padding: EdgeInsets.symmetric(horizontal: 12, vertical: 4),
              child: CharacterChip(),
            ),

            const SizedBox(height: 12),
            const Divider(height: 1),
            const SizedBox(height: 8),

            // Navigation groups
            Expanded(
              child: ListView(
                padding: const EdgeInsets.symmetric(vertical: 4),
                children: [
                  for (final group in navGroups) ...[
                    _GroupLabel(group.label),
                    for (final route in group.routes)
                      _NavItem(
                        route: route,
                        selected: route == current,
                        onTap: () => ref
                            .read(currentRouteProvider.notifier)
                            .go(route),
                      ),
                    const SizedBox(height: 4),
                  ],
                ],
              ),
            ),

            const Divider(height: 1),

            // Settings / bottom area
            ListTile(
              dense: true,
              leading: const Icon(Icons.settings_outlined, size: 20),
              title: const Text('Settings'),
              onTap: () {}, // TODO: settings screen
            ),
          ],
        ),
      ),
    );
  }
}

class _GroupLabel extends StatelessWidget {
  final String label;
  const _GroupLabel(this.label);

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.fromLTRB(16, 8, 16, 2),
      child: Text(
        label.toUpperCase(),
        style: Theme.of(context).textTheme.labelSmall?.copyWith(
              color: Theme.of(context).colorScheme.onSurfaceVariant,
              letterSpacing: 1.2,
            ),
      ),
    );
  }
}

class _NavItem extends ConsumerWidget {
  final AppRoute route;
  final bool selected;
  final VoidCallback onTap;

  const _NavItem({
    required this.route,
    required this.selected,
    required this.onTap,
  });

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final theme = Theme.of(context);
    final color = selected
        ? theme.colorScheme.primary
        : theme.colorScheme.onSurfaceVariant;

    return Padding(
      padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 1),
      child: Material(
        color: selected
            ? theme.colorScheme.primaryContainer.withValues(alpha: 0.3)
            : Colors.transparent,
        borderRadius: BorderRadius.circular(8),
        child: InkWell(
          onTap: onTap,
          borderRadius: BorderRadius.circular(8),
          child: Padding(
            padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 9),
            child: Row(
              children: [
                Icon(
                  selected ? route.selectedIcon : route.icon,
                  size: 18,
                  color: color,
                ),
                const SizedBox(width: 12),
                Expanded(
                  child: Text(
                    route.label,
                    overflow: TextOverflow.ellipsis,
                    style: theme.textTheme.bodyMedium?.copyWith(
                      color: color,
                      fontWeight:
                          selected ? FontWeight.w600 : FontWeight.normal,
                    ),
                  ),
                ),
              ],
            ),
          ),
        ),
      ),
    );
  }
}
