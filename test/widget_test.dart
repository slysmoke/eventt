import 'package:drift/native.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';

import 'package:eventt/core/database/app_database.dart';
import 'package:eventt/core/database/database_provider.dart';
import 'package:eventt/core/sde/sde_provider.dart';
import 'package:eventt/main.dart';

void main() {
  testWidgets('App renders without crashing', (tester) async {
    final db = AppDatabase.forTesting(NativeDatabase.memory());

    await tester.pumpWidget(
      ProviderScope(
        overrides: [
          databaseProvider.overrideWithValue(db),
          sdeInitProvider.overrideWith(_StubSdeInitNotifier.new),
        ],
        child: const App(),
      ),
    );

    expect(find.text('eventt'), findsOneWidget);

    // Unmount widget tree and flush drift stream cleanup timers.
    await tester.pumpWidget(const SizedBox.shrink());
    await tester.pump(Duration.zero);
    await db.close();
  });
}

/// Overrides SDE init to skip network calls and path_provider in tests.
class _StubSdeInitNotifier extends SdeInitNotifier {
  @override
  SdeInitState build() => const SdeInitState(isReady: false);
}
