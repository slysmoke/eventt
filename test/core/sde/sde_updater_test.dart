import 'dart:io';

import 'package:dio/dio.dart';
import 'package:drift/native.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:mocktail/mocktail.dart';

import 'package:eventt/core/database/app_database.dart';
import 'package:eventt/core/sde/sde_updater.dart';

class _MockDio extends Mock implements Dio {}

void main() {
  late AppDatabase db;
  late _MockDio dio;
  late Directory tempDir;

  setUp(() async {
    db = AppDatabase.forTesting(NativeDatabase.memory());
    dio = _MockDio();
    tempDir = await Directory.systemTemp.createTemp('sde_test_');

    // Default stubs
    registerFallbackValue(RequestOptions(path: ''));
    registerFallbackValue(Options());
  });

  tearDown(() async {
    await db.close();
    await tempDir.delete(recursive: true);
  });

  SdeUpdater makeUpdater() => SdeUpdater(
        db: db,
        dio: dio,
        overrideDbPath: '${tempDir.path}/eve.db',
      );

  group('SdeUpdater.needsUpdate()', () {
    test('returns true when database file does not exist', () async {
      when(() => dio.get(
            SdeUpdater.versionUrl,
            options: any(named: 'options'),
          )).thenAnswer((_) async => Response(
            requestOptions: RequestOptions(path: SdeUpdater.versionUrl),
            statusCode: 200,
            data: {'sdeVersion': 'v20250101'},
          ));

      final updater = makeUpdater();
      expect(await updater.needsUpdate(), isTrue);
    });

    test('returns false when stored version matches remote', () async {
      await db.setSetting('sde_version', 'v20250101');
      // Create a dummy file so it "exists"
      File('${tempDir.path}/eve.db').writeAsStringSync('dummy');

      when(() => dio.get(
            SdeUpdater.versionUrl,
            options: any(named: 'options'),
          )).thenAnswer((_) async => Response(
            requestOptions: RequestOptions(path: SdeUpdater.versionUrl),
            statusCode: 200,
            data: {'sdeVersion': 'v20250101'},
          ));

      final updater = makeUpdater();
      expect(await updater.needsUpdate(), isFalse);
    });

    test('returns true when remote version differs from stored', () async {
      await db.setSetting('sde_version', 'v20240101');
      File('${tempDir.path}/eve.db').writeAsStringSync('dummy');

      when(() => dio.get(
            SdeUpdater.versionUrl,
            options: any(named: 'options'),
          )).thenAnswer((_) async => Response(
            requestOptions: RequestOptions(path: SdeUpdater.versionUrl),
            statusCode: 200,
            data: {'sdeVersion': 'v20250101'},
          ));

      final updater = makeUpdater();
      expect(await updater.needsUpdate(), isTrue);
    });

    test('returns false on network error (do not force update)', () async {
      File('${tempDir.path}/eve.db').writeAsStringSync('dummy');

      when(() => dio.get(
            SdeUpdater.versionUrl,
            options: any(named: 'options'),
          )).thenThrow(DioException(
        requestOptions: RequestOptions(path: SdeUpdater.versionUrl),
        type: DioExceptionType.connectionError,
      ));

      final updater = makeUpdater();
      expect(await updater.needsUpdate(), isFalse);
    });
  });

  group('SdeUpdater.update()', () {
    test('saves version to settings after successful download', () async {
      when(() => dio.get(
            SdeUpdater.versionUrl,
            options: any(named: 'options'),
          )).thenAnswer((_) async => Response(
            requestOptions: RequestOptions(path: SdeUpdater.versionUrl),
            statusCode: 200,
            data: {'sdeVersion': 'v20250601'},
          ));

      when(() => dio.download(
            SdeUpdater.dbUrl,
            any(),
            onReceiveProgress: any(named: 'onReceiveProgress'),
          )).thenAnswer((_) async => Response(
            requestOptions: RequestOptions(path: SdeUpdater.dbUrl),
            statusCode: 200,
          ));

      final updater = makeUpdater();
      await updater.update();

      final saved = await db.getSetting('sde_version');
      expect(saved, 'v20250601');
    });
  });
}
