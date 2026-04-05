import 'package:flutter/material.dart';

import '../../../core/ui/placeholder_screen.dart';

class JournalScreen extends StatelessWidget {
  const JournalScreen({super.key});

  @override
  Widget build(BuildContext context) {
    return const PlaceholderScreen(
      icon: Icons.account_balance_wallet,
      title: 'Journal',
      description: 'Wallet journal with tax, broker fee, '
          'and income/expense analytics over time.',
    );
  }
}
