import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';

import 'package:eve_ntt/features/transactions/presentation/transactions_screen.dart';
import 'package:eve_ntt/core/ui/placeholder_screen.dart';

void main() {
  group('TransactionsScreen', () {
    testWidgets('is a StatelessWidget', (tester) async {
      await tester.pumpWidget(
        const MaterialApp(
          home: TransactionsScreen(),
        ),
      );
      expect(find.byType(TransactionsScreen), findsOneWidget);
    });

    testWidgets('shows PlaceholderScreen with correct icon', (tester) async {
      await tester.pumpWidget(
        const MaterialApp(
          home: TransactionsScreen(),
        ),
      );
      expect(find.byIcon(Icons.receipt_long), findsOneWidget);
    });

    testWidgets('shows correct title', (tester) async {
      await tester.pumpWidget(
        const MaterialApp(
          home: TransactionsScreen(),
        ),
      );
      expect(find.text('Transactions'), findsOneWidget);
    });

    testWidgets('shows description', (tester) async {
      await tester.pumpWidget(
        const MaterialApp(
          home: TransactionsScreen(),
        ),
      );
      expect(
        find.textContaining('Wallet transaction history'),
        findsOneWidget,
      );
    });
  });
}
