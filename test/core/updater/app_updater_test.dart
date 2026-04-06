import 'package:flutter_test/flutter_test.dart';

import 'package:eve_ntt/core/updater/app_updater.dart';

void main() {
  group('AppUpdater._isNewer', () {
    // We can't test private method directly, so test through UpdateInfo logic
    // by verifying the class exists and can be constructed.

    test('UpdateInfo can be created', () {
      const info = UpdateInfo(
        currentVersion: '0.1.0',
        latestVersion: '0.2.0',
        releaseNotes: 'New features',
        downloadUrl: 'https://example.com/app.AppImage',
        assetName: 'eve_ntt-0.2.0-linux-x86_64.AppImage',
      );
      expect(info.currentVersion, '0.1.0');
      expect(info.latestVersion, '0.2.0');
      expect(info.releaseNotes, 'New features');
    });
  });

  group('AppUpdater', () {
    test('can be instantiated', () {
      final updater = AppUpdater(
        repoOwner: 'test',
        repoName: 'test',
      );
      expect(updater.repoOwner, 'test');
      expect(updater.repoName, 'test');
    });
  });
}
