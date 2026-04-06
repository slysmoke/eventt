import 'dart:convert';

import 'package:dio/dio.dart';
import 'package:drift/drift.dart' show Value;
import 'package:drift/native.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:mocktail/mocktail.dart';

import 'package:eve_ntt/core/database/app_database.dart';
import 'package:eve_ntt/core/esi/esi_client.dart';
import 'package:eve_ntt/features/auth/data/character_repository.dart';

class _MockEsiClient extends Mock implements EsiClient {}

void main() {
  late AppDatabase db;
  late _MockEsiClient esi;
  late CharacterRepository repo;

  const characterId = 2114794365;
  const accessToken = 'Bearer test_token';

  setUp(() {
    db = AppDatabase.forTesting(NativeDatabase.memory());
    esi = _MockEsiClient();
    repo = CharacterRepository(esi: esi, db: db);

    registerFallbackValue(Options());
  });

  tearDown(() => db.close());

  group('CharacterRepository.fetchAndSave()', () {
    test('saves character name and corporation_id to database', () async {
      when(() => esi.get(
            '/characters/$characterId/',
            accessToken: accessToken,
          )).thenAnswer((_) async => Response(
            requestOptions: RequestOptions(path: ''),
            statusCode: 200,
            data: jsonEncode({
              'name': 'Test Pilot',
              'corporation_id': 98000001,
              'alliance_id': 99000001,
            }),
          ));

      await repo.fetchAndSave(characterId, accessToken);

      final saved = await (db.select(db.characters)
            ..where((t) => t.id.equals(characterId)))
          .getSingleOrNull();

      expect(saved, isNotNull);
      expect(saved!.name, 'Test Pilot');
      expect(saved.corporationId, 98000001);
    });

    test('constructs portrait URL from EVE image server', () async {
      when(() => esi.get(
            '/characters/$characterId/',
            accessToken: accessToken,
          )).thenAnswer((_) async => Response(
            requestOptions: RequestOptions(path: ''),
            statusCode: 200,
            data: jsonEncode({'name': 'Pilot', 'corporation_id': 1}),
          ));

      await repo.fetchAndSave(characterId, accessToken);

      final saved = await (db.select(db.characters)
            ..where((t) => t.id.equals(characterId)))
          .getSingleOrNull();

      expect(
        saved!.portraitUrl,
        'https://images.evetech.net/characters/$characterId/portrait?size=128',
      );
    });

    test('updates existing character on conflict', () async {
      // Pre-insert with old name
      await db.into(db.characters).insert(CharactersCompanion.insert(
            id: const Value(characterId),
            name: 'Old Name',
            addedAt: DateTime(2024),
          ));

      when(() => esi.get(
            '/characters/$characterId/',
            accessToken: accessToken,
          )).thenAnswer((_) async => Response(
            requestOptions: RequestOptions(path: ''),
            statusCode: 200,
            data: jsonEncode({'name': 'New Name', 'corporation_id': 1}),
          ));

      await repo.fetchAndSave(characterId, accessToken);

      final all = await db.select(db.characters).get();
      expect(all.length, 1);
      expect(all.first.name, 'New Name');
    });

    test('works when alliance_id is absent', () async {
      when(() => esi.get(
            '/characters/$characterId/',
            accessToken: accessToken,
          )).thenAnswer((_) async => Response(
            requestOptions: RequestOptions(path: ''),
            statusCode: 200,
            // No alliance_id — NPC corp character
            data: jsonEncode({'name': 'NPC Pilot', 'corporation_id': 1000001}),
          ));

      await expectLater(
        repo.fetchAndSave(characterId, accessToken),
        completes,
      );
    });
  });
}
