import 'package:flutter/material.dart';

import '../../../core/ui/placeholder_screen.dart';

class AssetsScreen extends StatelessWidget {
  const AssetsScreen({super.key});

  @override
  Widget build(BuildContext context) {
    return const PlaceholderScreen(
      icon: Icons.inventory_2,
      title: 'Assets',
      description: 'Assets across all characters with current market value.',
    );
  }
}
