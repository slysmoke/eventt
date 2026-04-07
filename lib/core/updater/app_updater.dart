import 'dart:ffi';
import 'dart:io';

import 'package:dio/dio.dart';
import 'package:package_info_plus/package_info_plus.dart';

/// Info about a newer available release from GitHub.
class UpdateInfo {
  final String currentVersion;
  final String latestVersion;
  final String releaseNotes;
  final String downloadUrl;
  final String assetName;

  const UpdateInfo({
    required this.currentVersion,
    required this.latestVersion,
    required this.releaseNotes,
    required this.downloadUrl,
    required this.assetName,
  });
}

/// Checks GitHub Releases for newer versions and downloads the update.
class AppUpdater {
  final String repoOwner;
  final String repoName;
  final Dio _dio;

  AppUpdater({
    required this.repoOwner,
    required this.repoName,
    Dio? dio,
  }) : _dio = dio ?? Dio();

  /// Checks if a newer version is available on GitHub Releases.
  /// Returns [UpdateInfo] if update available, null if up-to-date.
  Future<UpdateInfo?> checkForUpdate() async {
    final packageInfo = await PackageInfo.fromPlatform();
    final currentVersion = packageInfo.version; // e.g. "0.1.0"

    try {
      final response = await _dio.get(
        'https://api.github.com/repos/$repoOwner/$repoName/releases/latest',
        options: Options(
          headers: {'Accept': 'application/vnd.github.v3+json'},
        ),
      );

      if (response.statusCode != 200 || response.data == null) return null;

      final data = response.data as Map<String, dynamic>;
      final latestVersion = (data['tag_name'] as String).replaceFirst('v', '');
      final releaseNotes = (data['body'] as String?) ?? 'No release notes.';

      if (!_isNewer(currentVersion, latestVersion)) return null;

      // Find the right asset for this platform
      final assets = data['assets'] as List? ?? [];
      final asset = _findPlatformAsset(assets);
      if (asset == null) return null;

      return UpdateInfo(
        currentVersion: currentVersion,
        latestVersion: latestVersion,
        releaseNotes: releaseNotes,
        downloadUrl: asset['browser_download_url'] as String,
        assetName: asset['name'] as String,
      );
    } catch (_) {
      return null;
    }
  }

  /// Downloads the update to a temporary location.
  /// Returns the path to the downloaded file.
  Future<String> downloadUpdate({
    required String url,
    required String fileName,
    void Function(double)? onProgress,
  }) async {
    final tempDir = Directory.systemTemp;
    final filePath = '${tempDir.path}/$fileName';

    await _dio.download(
      url,
      filePath,
      onReceiveProgress: (received, total) {
        if (total > 0 && onProgress != null) {
          onProgress(received / total);
        }
      },
    );

    return filePath;
  }

  /// Opens the downloaded file for installation.
  /// Returns true if the platform supports auto-install.
  Future<bool> installUpdate(String filePath) async {
    try {
      if (Platform.isLinux) {
        // Make executable and offer to run
        await Process.run('chmod', ['+x', filePath]);
        await Process.start(filePath, [], mode: ProcessStartMode.detached);
        return true;
      } else if (Platform.isWindows) {
        await Process.start(filePath, [], mode: ProcessStartMode.detached);
        return true;
      } else if (Platform.isMacOS) {
        // Mount DMG and copy to Applications
        await Process.run('hdiutil', ['attach', filePath]);
        // User needs to manually drag to Applications
        return false;
      }
    } catch (_) {
      return false;
    }
    return false;
  }

  /// Opens the GitHub releases page in browser (fallback).
  Future<void> openReleasePage() async {
    final url = 'https://github.com/$repoOwner/$repoName/releases';
    if (Platform.isLinux) {
      await Process.run('xdg-open', [url]);
    } else if (Platform.isWindows) {
      await Process.run('cmd', ['/c', 'start', url]);
    } else if (Platform.isMacOS) {
      await Process.run('open', [url]);
    }
  }

  // ---------------------------------------------------------------------------
  // Helpers
  // ---------------------------------------------------------------------------

  /// Simple semver comparison (handles X.Y.Z format).
  bool _isNewer(String current, String latest) {
    if (current == latest) return false;

    final currentParts = current.split('.').map(int.tryParse).toList();
    final latestParts = latest.split('.').map(int.tryParse).toList();

    for (var i = 0; i < 3; i++) {
      final c = currentParts.length > i ? (currentParts[i] ?? 0) : 0;
      final l = latestParts.length > i ? (latestParts[i] ?? 0) : 0;
      if (l > c) return true;
      if (l < c) return false;
    }
    return false;
  }

  /// Finds the best asset for the current platform.
  /// For macOS, prefers the architecture-specific DMG (arm64 vs x86_64).
  Map<String, dynamic>? _findPlatformAsset(List<dynamic> assets) {
    if (Platform.isLinux) {
      for (final asset in assets) {
        final lower = (asset['name'] as String).toLowerCase();
        if (lower.contains('linux') && lower.endsWith('.appimage')) return asset;
      }
      for (final asset in assets) {
        if ((asset['name'] as String).toLowerCase().endsWith('.appimage')) return asset;
      }
    } else if (Platform.isWindows) {
      for (final asset in assets) {
        final lower = (asset['name'] as String).toLowerCase();
        if (lower.contains('windows') && lower.endsWith('.exe')) return asset;
      }
      for (final asset in assets) {
        if ((asset['name'] as String).toLowerCase().endsWith('.exe')) return asset;
      }
    } else if (Platform.isMacOS) {
      // Abi.current() reflects the actual ABI being used — correct even under Rosetta.
      final archTag = Abi.current() == Abi.macosArm64 ? 'arm64' : 'x86_64';
      for (final asset in assets) {
        final lower = (asset['name'] as String).toLowerCase();
        if (lower.endsWith('.dmg') && lower.contains(archTag)) return asset;
      }
      // Fallback: any DMG if arch-specific not found.
      for (final asset in assets) {
        if ((asset['name'] as String).toLowerCase().endsWith('.dmg')) return asset;
      }
    }
    return null;
  }
}
