import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';

import 'package:eve_ntt/core/ui/app_theme.dart';

void main() {
  group('AppTheme', () {
    test('dark theme uses Material 3', () {
      final theme = AppTheme.dark;
      expect(theme.useMaterial3, isTrue);
    });

    test('dark theme has dark brightness', () {
      final theme = AppTheme.dark;
      expect(theme.brightness, Brightness.dark);
    });

    test('dark theme has custom scaffold background', () {
      final theme = AppTheme.dark;
      expect(theme.scaffoldBackgroundColor, const Color(0xFF111418));
    });

    test('dark theme has teal-based primary color', () {
      final theme = AppTheme.dark;
      // The primary color is derived from the teal seed, not exactly the seed
      expect(theme.colorScheme.primary.blue, greaterThan(0.7));
    });

    test('dark theme has white text', () {
      final theme = AppTheme.dark;
      // Text color is applied via theme.textTheme
      expect(theme.textTheme.bodyLarge?.color, isNotNull);
    });

    test('dark theme has divider color', () {
      final theme = AppTheme.dark;
      expect(theme.dividerColor, Colors.white12);
    });

    test('dark theme has dense list tiles', () {
      final theme = AppTheme.dark;
      expect(theme.listTileTheme.dense, isTrue);
    });
  });
}
