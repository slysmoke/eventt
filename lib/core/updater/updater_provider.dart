import 'package:dio/dio.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import 'app_updater.dart';

// GitHub repo coordinates — change to your actual repo.
const _kRepoOwner = 'slysmoke';
const _kRepoName = 'eventt';

final _dioProvider = Provider<Dio>((ref) => Dio());

final appUpdaterProvider = Provider<AppUpdater>((ref) {
  return AppUpdater(
    repoOwner: _kRepoOwner,
    repoName: _kRepoName,
    dio: ref.watch(_dioProvider),
  );
});

// ---------------------------------------------------------------------------
// Update check state
// ---------------------------------------------------------------------------

class UpdateCheckState {
  final bool isChecking;
  final bool isDownloading;
  final double downloadProgress;
  final UpdateInfo? updateInfo;
  final String? error;
  final String? downloadedPath;

  const UpdateCheckState({
    this.isChecking = false,
    this.isDownloading = false,
    this.downloadProgress = 0,
    this.updateInfo,
    this.error,
    this.downloadedPath,
  });

  UpdateCheckState copyWith({
    bool? isChecking,
    bool? isDownloading,
    double? downloadProgress,
    UpdateInfo? updateInfo,
    String? error,
    String? downloadedPath,
  }) =>
      UpdateCheckState(
        isChecking: isChecking ?? this.isChecking,
        isDownloading: isDownloading ?? this.isDownloading,
        downloadProgress: downloadProgress ?? this.downloadProgress,
        updateInfo: updateInfo ?? this.updateInfo,
        error: error ?? this.error,
        downloadedPath: downloadedPath ?? this.downloadedPath,
      );
}

final updateCheckProvider =
    NotifierProvider<UpdateCheckNotifier, UpdateCheckState>(
  UpdateCheckNotifier.new,
);

class UpdateCheckNotifier extends Notifier<UpdateCheckState> {
  @override
  UpdateCheckState build() => const UpdateCheckState();

  /// Checks for updates. Call this on app startup.
  Future<void> check() async {
    state = state.copyWith(isChecking: true, error: null);
    try {
      final updater = ref.read(appUpdaterProvider);
      final info = await updater.checkForUpdate();
      state = state.copyWith(
        isChecking: false,
        updateInfo: info,
      );
    } catch (e) {
      state = state.copyWith(
        isChecking: false,
        error: e.toString(),
      );
    }
  }

  /// Downloads the update.
  Future<void> download() async {
    final info = state.updateInfo;
    if (info == null) return;

    state = state.copyWith(isDownloading: true, downloadProgress: 0);
    try {
      final updater = ref.read(appUpdaterProvider);
      final path = await updater.downloadUpdate(
        url: info.downloadUrl,
        fileName: info.assetName,
        onProgress: (p) => state = state.copyWith(downloadProgress: p),
      );
      state = state.copyWith(
        isDownloading: false,
        downloadedPath: path,
      );
    } catch (e) {
      state = state.copyWith(
        isDownloading: false,
        error: e.toString(),
      );
    }
  }

  /// Installs the downloaded update.
  Future<bool> install() async {
    final path = state.downloadedPath;
    if (path == null) return false;

    final updater = ref.read(appUpdaterProvider);
    final success = await updater.installUpdate(path);
    return success;
  }

  /// Opens GitHub releases page as fallback.
  Future<void> openReleasePage() async {
    final updater = ref.read(appUpdaterProvider);
    await updater.openReleasePage();
  }

  /// Dismisses the update notification.
  void dismiss() {
    state = const UpdateCheckState();
  }
}
