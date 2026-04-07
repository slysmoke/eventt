import 'dart:ffi';
import 'dart:io';

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:sqlite3/open.dart';
import 'package:sqlite3/sqlite3.dart';
import 'package:window_manager/window_manager.dart';

import 'core/ui/app_theme.dart';
import 'features/shell/presentation/shell_screen.dart';

/// Attempt to load libsqlite3.so from common Linux paths.
DynamicLibrary? _tryLoadSqlite3() {
  final candidatePaths = [
    '/usr/lib/x86_64-linux-gnu/libsqlite3.so.0',
    '/usr/lib/x86_64-linux-gnu/libsqlite3.so',
    '/usr/lib64/libsqlite3.so.0',
    '/usr/lib64/libsqlite3.so',
    '/usr/lib/libsqlite3.so.0',
    'libsqlite3.so.0', // relies on LD_LIBRARY_PATH
    'libsqlite3.so', // relies on LD_LIBRARY_PATH
  ];

  for (final path in candidatePaths) {
    try {
      return DynamicLibrary.open(path);
    } catch (_) {
      // try next
    }
  }
  return null;
}

void main() async {
  WidgetsFlutterBinding.ensureInitialized();
  await windowManager.ensureInitialized();

  // On Linux, ensure sqlite3 is available. The default loading mechanism may
  // fail in some environments (AppImage, nix-shell). Fall back to system lib.
  if (Platform.isLinux) {
    try {
      // Try default loading (will attempt DynamicLibrary.open('libsqlite3.so'))
      // We just verify it works by opening a test DB
      sqlite3.openInMemory().dispose();
    } catch (_) {
      // Fallback: load system libsqlite3 explicitly
      final lib = _tryLoadSqlite3();
      if (lib != null) {
        open.overrideFor(OperatingSystem.linux, () => lib);
        debugPrint('sqlite3: loaded system library as fallback');
      } else {
        debugPrint(
          'WARNING: sqlite3 not available. Database features will not work.',
        );
      }
    }
  }

  runApp(const ProviderScope(child: App()));
}

class App extends StatelessWidget {
  const App({super.key});

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: 'EVE Night Trade Tools',
      debugShowCheckedModeBanner: false,
      theme: AppTheme.dark,
      home: const ShellScreen(),
    );
  }
}
