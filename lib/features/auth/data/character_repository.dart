import 'dart:convert';

import 'package:dio/dio.dart' show Response;
import 'package:drift/drift.dart' show Value;

import '../../../core/database/app_database.dart';
import '../../../core/esi/esi_client.dart';
import 'corporation_repository.dart';

/// Safely extract JSON from ESI response, handling both Map and String.
Map<String, dynamic> _extractData(Response<dynamic> response) {
  // Dio may have already parsed it as a Map
  if (response.data is Map<String, dynamic>) {
    return response.data as Map<String, dynamic>;
  }
  // If it's a raw Map (not typed), convert
  if (response.data is Map) {
    return Map<String, dynamic>.from(response.data as Map);
  }
  // If it's a String, try parsing JSON
  if (response.data is String) {
    final str = response.data as String;
    return jsonDecode(str) as Map<String, dynamic>;
  }
  throw FormatException('Unexpected response type: ${response.data.runtimeType}');
}

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

    final data = _extractData(response);

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
