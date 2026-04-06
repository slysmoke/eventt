import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';

import 'package:eve_ntt/core/ui/placeholder_screen.dart';

void main() {
  group('PlaceholderScreen', () {
    testWidgets('displays title', (tester) async {
      await tester.pumpWidget(
        MaterialApp(
          home: Scaffold(
            body: PlaceholderScreen(
              icon: Icons.construction,
              title: 'Test Screen',
              description: 'Under construction',
            ),
          ),
        ),
      );

      expect(find.text('Test Screen'), findsOneWidget);
    });

    testWidgets('displays description', (tester) async {
      await tester.pumpWidget(
        MaterialApp(
          home: Scaffold(
            body: PlaceholderScreen(
              icon: Icons.construction,
              title: 'Test Screen',
              description: 'Under construction',
            ),
          ),
        ),
      );

      expect(find.text('Under construction'), findsOneWidget);
    });

    testWidgets('displays icon', (tester) async {
      await tester.pumpWidget(
        MaterialApp(
          home: Scaffold(
            body: PlaceholderScreen(
              icon: Icons.construction,
              title: 'Test Screen',
              description: 'Under construction',
            ),
          ),
        ),
      );

      expect(find.byIcon(Icons.construction), findsOneWidget);
    });

    testWidgets('uses provided icon', (tester) async {
      await tester.pumpWidget(
        MaterialApp(
          home: Scaffold(
            body: PlaceholderScreen(
              icon: Icons.settings,
              title: 'Test Screen',
              description: 'Under construction',
            ),
          ),
        ),
      );

      expect(find.byIcon(Icons.settings), findsOneWidget);
    });
  });
}
