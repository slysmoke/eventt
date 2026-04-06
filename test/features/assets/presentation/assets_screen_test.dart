import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';

import 'package:eve_ntt/features/assets/presentation/assets_screen.dart';
import 'package:eve_ntt/core/ui/placeholder_screen.dart';

void main() {
  group('AssetsScreen', () {
    testWidgets('is a StatelessWidget', (tester) async {
      await tester.pumpWidget(
        const MaterialApp(
          home: AssetsScreen(),
        ),
      );
      expect(find.byType(AssetsScreen), findsOneWidget);
    });

    testWidgets('shows PlaceholderScreen with correct icon', (tester) async {
      await tester.pumpWidget(
        const MaterialApp(
          home: AssetsScreen(),
        ),
      );
      expect(find.byIcon(Icons.inventory_2), findsOneWidget);
    });

    testWidgets('shows correct title', (tester) async {
      await tester.pumpWidget(
        const MaterialApp(
          home: AssetsScreen(),
        ),
      );
      expect(find.text('Assets'), findsOneWidget);
    });

    testWidgets('shows description', (tester) async {
      await tester.pumpWidget(
        const MaterialApp(
          home: AssetsScreen(),
        ),
      );
      expect(
        find.textContaining('Assets across all characters'),
        findsOneWidget,
      );
    });
  });
}
