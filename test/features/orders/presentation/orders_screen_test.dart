import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';

import 'package:eve_ntt/features/orders/presentation/orders_screen.dart';
import 'package:eve_ntt/core/ui/placeholder_screen.dart';

void main() {
  group('OrdersScreen', () {
    testWidgets('is a StatelessWidget', (tester) async {
      await tester.pumpWidget(
        const MaterialApp(
          home: OrdersScreen(),
        ),
      );
      expect(find.byType(OrdersScreen), findsOneWidget);
    });

    testWidgets('shows PlaceholderScreen with correct icon', (tester) async {
      await tester.pumpWidget(
        const MaterialApp(
          home: OrdersScreen(),
        ),
      );
      expect(find.byIcon(Icons.list_alt), findsOneWidget);
    });

    testWidgets('shows correct title', (tester) async {
      await tester.pumpWidget(
        const MaterialApp(
          home: OrdersScreen(),
        ),
      );
      expect(find.text('My Orders'), findsOneWidget);
    });

    testWidgets('shows description', (tester) async {
      await tester.pumpWidget(
        const MaterialApp(
          home: OrdersScreen(),
        ),
      );
      expect(
        find.textContaining('Active orders'),
        findsOneWidget,
      );
    });
  });
}
