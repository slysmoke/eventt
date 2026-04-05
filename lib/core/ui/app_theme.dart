import 'package:flutter/material.dart';

/// EVE-inspired dark theme with teal accent.
abstract final class AppTheme {
  // EVE Online teal
  static const _seed = Color(0xFF00B4BF);

  static ThemeData get dark => ThemeData(
        useMaterial3: true,
        brightness: Brightness.dark,
        colorScheme: ColorScheme.fromSeed(
          seedColor: _seed,
          brightness: Brightness.dark,
        ),
        // Slightly tighter default text
        textTheme: const TextTheme(
          headlineMedium: TextStyle(fontWeight: FontWeight.w300),
          titleLarge: TextStyle(fontWeight: FontWeight.w500),
        ).apply(
          bodyColor: Colors.white,
          displayColor: Colors.white,
        ),
        scaffoldBackgroundColor: const Color(0xFF111418),
        dividerColor: Colors.white12,
        listTileTheme: const ListTileThemeData(
          dense: true,
          contentPadding: EdgeInsets.symmetric(horizontal: 16),
        ),
      );
}
