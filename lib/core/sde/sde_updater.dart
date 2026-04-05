import 'dart:io';

import 'package:dio/dio.dart';
import 'package:path/path.dart' as p;
import 'package:path_provider/path_provider.dart';

import '../database/app_database.dart';

/// Downloads and updates the EVE Static Data Export (SDE) SQLite database.
///
/// Source: https://github.com/slysmoke/evernus-db
///
/// On startup:
/// 1. [needsUpdate] → compare stored sde_version with remote latest_version.json
/// 2. [update] → download eve.db, save version to AppSettings
class SdeUpdater {
  static const versionUrl =
      'https://raw.githubusercontent.com/slysmoke/evernus-db/main/latest_version.json';
  static const dbUrl =
      'https://raw.githubusercontent.com/slysmoke/evernus-db/main/eve.db';

  static const _versionKey = 'sde_version';

  final AppDatabase _db;
  final Dio _dio;

  /// Overrides the db file path — used in tests to avoid path_provider.
  final String? _overrideDbPath;

  SdeUpdater({
    required AppDatabase db,
    Dio? dio,
    String? overrideDbPath,
  })  : _db = db,
        _dio = dio ?? Dio(),
        _overrideDbPath = overrideDbPath;

  Future<String> get dbPath async {
    if (_overrideDbPath != null) return _overrideDbPath;
    final dir = await getApplicationSupportDirectory();
    return p.join(dir.path, 'eve.db');
  }

  /// Returns true if the SDE database needs to be downloaded or updated.
  /// On network error returns false (don't force-update when offline).
  Future<bool> needsUpdate() async {
    final path = await dbPath;
    if (!File(path).existsSync()) return true;

    try {
      final response = await _dio.get(versionUrl);
      final remote = (response.data as Map<String, dynamic>)['sdeVersion'] as String;
      final local = await _db.getSetting(_versionKey);
      return remote != local;
    } catch (_) {
      return false;
    }
  }

  /// Downloads the latest SDE database and saves the version.
  Future<void> update({
    void Function(int received, int total)? onProgress,
  }) async {
    final versionResponse = await _dio.get(versionUrl);
    final version =
        (versionResponse.data as Map<String, dynamic>)['sdeVersion'] as String;

    final path = await dbPath;
    await _dio.download(
      dbUrl,
      path,
      onReceiveProgress: onProgress,
    );

    await _db.setSetting(_versionKey, version);
  }
}
