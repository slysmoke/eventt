import 'package:flutter/material.dart';

import '../../../core/ui/placeholder_screen.dart';

class DashboardScreen extends StatelessWidget {
  const DashboardScreen({super.key});

  @override
  Widget build(BuildContext context) {
    return const PlaceholderScreen(
      icon: Icons.dashboard,
      title: 'Dashboard',
      description: 'Aggregate P&L, profit by day/week/month, '
          'taxes and broker fees across all characters.',
    );
  }
}
