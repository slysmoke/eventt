import 'package:flutter/material.dart';

import '../../../core/ui/placeholder_screen.dart';

class MarginToolScreen extends StatelessWidget {
  const MarginToolScreen({super.key});

  @override
  Widget build(BuildContext context) {
    return const PlaceholderScreen(
      icon: Icons.calculate,
      title: 'Margin Tool',
      description: 'Reads price from clipboard. Calculates margin, taxes '
          'and broker fee for the active character. Copies the #1 order price. '
          'Works via global hotkey even when the app is in the background.',
    );
  }
}
