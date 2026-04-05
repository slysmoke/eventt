import 'package:flutter/material.dart';

import '../../../core/ui/placeholder_screen.dart';

class TransactionsScreen extends StatelessWidget {
  const TransactionsScreen({super.key});

  @override
  Widget build(BuildContext context) {
    return const PlaceholderScreen(
      icon: Icons.receipt_long,
      title: 'Transactions',
      description: 'Wallet transaction history with buy/sell breakdown '
          'and profit analysis per item type.',
    );
  }
}
