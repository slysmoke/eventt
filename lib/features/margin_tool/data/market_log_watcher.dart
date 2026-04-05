import 'dart:async';
import 'dart:io';

import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:path/path.dart' as p;

import '../../../core/market_log/market_log_parser.dart';
import '../../../core/settings/margin_settings_provider.dart';

/// The most recently modified market log file in the watched directory.
/// Null when no directory is configured or no .txt files exist.
final latestMarketLogProvider =
    StreamProvider.autoDispose<MarketLogFile?>((ref) async* {
  final settingsAsync = ref.watch(marginSettingsProvider);
  final settings = settingsAsync.value;
  final dir = settings?.marketLogDir;

  if (dir == null || dir.isEmpty) {
    yield null;
    return;
  }

  yield await _readLatest(dir);

  // Watch for FS changes
  final dirObj = Directory(dir);
  if (!dirObj.existsSync()) {
    yield null;
    return;
  }

  final watcher = dirObj.watch(events: FileSystemEvent.all);
  await for (final _ in watcher) {
    yield await _readLatest(dir);
  }
});

/// Returns the most recently modified .txt log file parsed into [MarketLogFile].
Future<MarketLogFile?> _readLatest(String dirPath) async {
  final dir = Directory(dirPath);
  if (!dir.existsSync()) return null;

  final files = dir
      .listSync()
      .whereType<File>()
      .where((f) => f.path.toLowerCase().endsWith('.txt'))
      .toList();

  if (files.isEmpty) return null;

  // Most recently modified file
  files.sort((a, b) =>
      b.lastModifiedSync().compareTo(a.lastModifiedSync()));

  final latest = files.first;
  try {
    final content = await latest.readAsString();
    final basename = p.basename(latest.path);
    return MarketLogParser.parse(content: content, filename: basename);
  } catch (_) {
    return null;
  }
}
