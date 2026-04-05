import 'package:flutter/material.dart';

import '../../../core/ui/placeholder_screen.dart';

class MarketBrowserScreen extends StatelessWidget {
  const MarketBrowserScreen({super.key});

  @override
  Widget build(BuildContext context) {
    return const PlaceholderScreen(
      icon: Icons.store,
      title: 'Market Browser',
      description: 'Browse market by region → group → item type. '
          'View current orders and historical data.',
    );
  }
}
