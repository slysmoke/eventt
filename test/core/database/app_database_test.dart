import 'package:drift/native.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:drift/drift.dart' show Value;

import 'package:eve_ntt/core/database/app_database.dart';

void main() {
  late AppDatabase db;

  setUp(() {
    db = AppDatabase.forTesting(NativeDatabase.memory());
  });

  tearDown(() => db.close());

  group('Characters', () {
    test('returns empty list when no characters exist', () async {
      final result = await db.select(db.characters).get();
      expect(result, isEmpty);
    });

    test('inserts and retrieves a character', () async {
      await db.into(db.characters).insert(
            CharactersCompanion.insert(
              id: const Value(12345),
              name: 'Test Pilot',
              addedAt: DateTime(2025),
            ),
          );

      final result = await db.select(db.characters).get();
      expect(result.length, 1);
      expect(result.first.id, 12345);
      expect(result.first.name, 'Test Pilot');
    });

    test('supports multiple characters', () async {
      await db.into(db.characters).insert(
            CharactersCompanion.insert(
              id: const Value(1),
              name: 'Alpha',
              addedAt: DateTime(2025),
            ),
          );
      await db.into(db.characters).insert(
            CharactersCompanion.insert(
              id: const Value(2),
              name: 'Beta',
              addedAt: DateTime(2025),
            ),
          );

      final result = await db.select(db.characters).get();
      expect(result.length, 2);
    });

    test('deletes a character by id', () async {
      await db.into(db.characters).insert(
            CharactersCompanion.insert(
              id: const Value(99),
              name: 'ToDelete',
              addedAt: DateTime(2025),
            ),
          );

      await (db.delete(db.characters)
            ..where((t) => t.id.equals(99)))
          .go();

      final result = await db.select(db.characters).get();
      expect(result, isEmpty);
    });
  });

  group('EsiCache', () {
    test('returns null for cache miss', () async {
      final result = await (db.select(db.esiCache)
            ..where((t) => t.url.equals('https://esi.evetech.net/test/')))
          .getSingleOrNull();
      expect(result, isNull);
    });

    test('stores and retrieves a cached response', () async {
      const url = 'https://esi.evetech.net/latest/characters/12345/';
      const body = '{"name":"Test Pilot","corporation_id":98000001}';

      await db.into(db.esiCache).insert(EsiCacheCompanion.insert(
            url: url,
            expiresAt: DateTime(2030),
            cachedAt: DateTime(2025),
            body: body,
          ));

      final result = await (db.select(db.esiCache)
            ..where((t) => t.url.equals(url)))
          .getSingleOrNull();

      expect(result, isNotNull);
      expect(result!.body, body);
      expect(result.etag, isNull);
    });

    test('stores etag when provided', () async {
      await db.into(db.esiCache).insert(EsiCacheCompanion.insert(
            url: 'https://esi.evetech.net/test/',
            etag: const Value('W/"abc123"'),
            expiresAt: DateTime(2030),
            cachedAt: DateTime(2025),
            body: '{}',
          ));

      final result = await (db.select(db.esiCache)
            ..where((t) => t.url.equals('https://esi.evetech.net/test/')))
          .getSingleOrNull();

      expect(result!.etag, 'W/"abc123"');
    });

    test('upserts on conflict (same url)', () async {
      const url = 'https://esi.evetech.net/latest/markets/';

      await db.into(db.esiCache).insert(EsiCacheCompanion.insert(
            url: url,
            expiresAt: DateTime(2025),
            cachedAt: DateTime(2025),
            body: '{"old":true}',
          ));

      await db.into(db.esiCache).insertOnConflictUpdate(EsiCacheCompanion.insert(
            url: url,
            expiresAt: DateTime(2030),
            cachedAt: DateTime(2025),
            body: '{"new":true}',
          ));

      final all = await db.select(db.esiCache).get();
      expect(all.length, 1);
      expect(all.first.body, '{"new":true}');
    });
  });

  group('AppSettings', () {
    test('returns null for missing key', () async {
      final result = await (db.select(db.appSettings)
            ..where((t) => t.key.equals('missing_key')))
          .getSingleOrNull();
      expect(result, isNull);
    });

    test('stores and retrieves a setting', () async {
      await db.into(db.appSettings).insert(
            AppSettingsCompanion.insert(key: 'sde_version', value: 'v20250101'),
          );

      final result = await (db.select(db.appSettings)
            ..where((t) => t.key.equals('sde_version')))
          .getSingleOrNull();

      expect(result!.value, 'v20250101');
    });

    test('upserts setting value', () async {
      await db.into(db.appSettings).insert(
            AppSettingsCompanion.insert(key: 'sde_version', value: 'v1'),
          );
      await db.into(db.appSettings).insertOnConflictUpdate(
            AppSettingsCompanion.insert(key: 'sde_version', value: 'v2'),
          );

      final all = await db.select(db.appSettings).get();
      expect(all.length, 1);
      expect(all.first.value, 'v2');
    });
  });
}
