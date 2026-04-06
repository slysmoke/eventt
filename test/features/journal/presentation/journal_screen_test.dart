import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';

import 'package:eve_ntt/features/journal/presentation/journal_screen.dart';
import 'package:eve_ntt/core/ui/placeholder_screen.dart';

void main() {
  group('JournalScreen', () {
    testWidgets('is a StatelessWidget', (tester) async {
      await tester.pumpWidget(
        const MaterialApp(
          home: JournalScreen(),
        ),
      );
      expect(find.byType(JournalScreen), findsOneWidget);
    });

    testWidgets('shows PlaceholderScreen with correct icon', (tester) async {
      await tester.pumpWidget(
        const MaterialApp(
          home: JournalScreen(),
        ),
      );
      expect(find.byIcon(Icons.account_balance_wallet), findsOneWidget);
    });

    testWidgets('shows correct title', (tester) async {
      await tester.pumpWidget(
        const MaterialApp(
          home: JournalScreen(),
        ),
      );
      expect(find.text('Journal'), findsOneWidget);
    });

    testWidgets('shows description', (tester) async {
      await tester.pumpWidget(
        const MaterialApp(
          home: JournalScreen(),
        ),
      );
      expect(
        find.textContaining('Wallet journal'),
        findsOneWidget,
      );
    });
  });
}
