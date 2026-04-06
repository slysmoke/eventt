import 'dart:io';

import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../database/database_provider.dart';
import 'sde_database.dart';
import 'sde_updater.dart';

// ---------------------------------------------------------------------------
// SdeUpdater provider
// ---------------------------------------------------------------------------

final sdeUpdaterProvider = Provider<SdeUpdater>((ref) {
  return SdeUpdater(db: ref.watch(databaseProvider));
});

// ---------------------------------------------------------------------------
// SDE init state
// ---------------------------------------------------------------------------

class SdeInitState {
  final bool isReady;
  final bool isDownloading;
  final double downloadProgress; // 0.0–1.0
  final String? error;
  final String? dbPath;

  const SdeInitState({
    this.isReady = false,
    this.isDownloading = false,
    this.downloadProgress = 0,
    this.error,
    this.dbPath,
  });

  SdeInitState copyWith({
    bool? isReady,
    bool? isDownloading,
    double? downloadProgress,
    String? error,
    String? dbPath,
  }) =>
      SdeInitState(
        isReady: isReady ?? this.isReady,
        isDownloading: isDownloading ?? this.isDownloading,
        downloadProgress: downloadProgress ?? this.downloadProgress,
        error: error ?? this.error,
        dbPath: dbPath ?? this.dbPath,
      );
}

// ---------------------------------------------------------------------------
// SDE init notifier — checks version on startup, downloads if needed
// ---------------------------------------------------------------------------

final sdeInitProvider =
    NotifierProvider<SdeInitNotifier, SdeInitState>(SdeInitNotifier.new);

class SdeInitNotifier extends Notifier<SdeInitState> {
  @override
  SdeInitState build() {
    Future.microtask(_init);
    return const SdeInitState();
  }

  Future<void> _init() async {
    final updater = ref.read(sdeUpdaterProvider);
    final path = await updater.dbPath;

    try {
      final needsUpdate = await updater.needsUpdate();
      if (needsUpdate) {
        state = state.copyWith(isDownloading: true, downloadProgress: 0);
        await updater.update(
          onProgress: (received, total) {
            if (total > 0) {
              state = state.copyWith(downloadProgress: received / total);
            }
          },
        );
      }

      if (!File(path).existsSync()) {
        state = state.copyWith(error: 'SDE database not found after update.');
        return;
      }

      state = state.copyWith(
        isReady: true,
        isDownloading: false,
        downloadProgress: 1.0,
        dbPath: path,
      );
    } catch (e) {
      state = state.copyWith(
        isDownloading: false,
        error: e.toString(),
      );
    }
  }

  Future<void> retry() => _init();
}

// ---------------------------------------------------------------------------
// SDE database provider — opens database once the path is ready
// ---------------------------------------------------------------------------

final sdeDatabaseProvider = Provider<SdeDatabase?>((ref) {
  final path = ref.watch(sdeInitProvider.select((s) => s.dbPath));
  if (path == null) return null;

  final db = SdeDatabase.open(path);
  ref.onDispose(db.close);

  // Link SDE database to user database for type name resolution
  // (this is set after databaseProvider is created)
  return db;
});
