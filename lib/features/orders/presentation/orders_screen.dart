import 'package:flutter/material.dart';

import '../../../core/ui/placeholder_screen.dart';

class OrdersScreen extends StatelessWidget {
  const OrdersScreen({super.key});

  @override
  Widget build(BuildContext context) {
    return const PlaceholderScreen(
      icon: Icons.list_alt,
      title: 'My Orders',
      description: 'Active orders for the selected character. '
          'Shows best market price, margin, and delta to become #1. '
          'Quick price update: copy new price → open in game.',
    );
  }
}
