import 'dart:convert';

import 'package:drift/drift.dart' show Value;

import '../../../core/database/app_database.dart';
import '../../../core/esi/esi_client.dart';
import 'corporation_repository.dart';

/// Fetches EVE character info from ESI and persists it to the local database.
class CharacterRepository {
  final EsiClient _esi;
  final AppDatabase _db;

  const CharacterRepository({required EsiClient esi, required AppDatabase db})
      : _esi = esi,
        _db = db;

  /// Fetches `/characters/{id}/` and saves the result.
  /// Portrait URL is constructed from the EVE image server (no extra request).
  Future<void> fetchAndSave(int characterId, String accessToken) async {
    final response = await _esi.get(
      '/characters/$characterId/',
      accessToken: accessToken,
    );

    final data = (response.data is String
            ? jsonDecode(response.data as String)
            : response.data)
        as Map<String, dynamic>;

    final name = data['name'] as String;
    final corporationId = (data['corporation_id'] as num?)?.toInt();
    final portraitUrl =
        'https://images.evetech.net/characters/$characterId/portrait?size=128';

    await _db.into(_db.characters).insertOnConflictUpdate(
          CharactersCompanion.insert(
            id: Value(characterId),
            name: name,
            corporationId: Value(corporationId),
            portraitUrl: Value(portraitUrl),
            addedAt: DateTime.now(),
          ),
        );

    // Fetch corporation info and populate corporationName
    if (corporationId != null) {
      final corpRepo = CorporationRepository(esi: _esi, db: _db);
      await corpRepo.fetchAndSave(corporationId);
      await corpRepo.updateCharacterCorporationName(characterId, corporationId);
    }
  }
}
