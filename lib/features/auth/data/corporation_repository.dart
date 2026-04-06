import 'dart:convert';

import 'package:drift/drift.dart' show Value;

import '../../../core/database/app_database.dart';
import '../../../core/esi/esi_client.dart';

/// Fetches corporation info from ESI and persists it to the local database.
class CorporationRepository {
  final EsiClient _esi;
  final AppDatabase _db;

  const CorporationRepository({required EsiClient esi, required AppDatabase db})
      : _esi = esi,
        _db = db;

  /// Fetches `/corporations/{id}/` (public endpoint, no auth needed).
  Future<Corporation?> fetchAndSave(int corporationId) async {
    try {
      final response = await _esi.get(
        '/corporations/$corporationId/',
      );

      final data = (response.data is String
              ? jsonDecode(response.data as String)
              : response.data)
          as Map<String, dynamic>;

      final name = data['name'] as String? ?? 'Unknown Corporation';
      final ticker = data['ticker'] as String?;
      final ceoId = (data['ceo_id'] as num?)?.toInt();
      final allianceId = (data['alliance_id'] as num?)?.toInt();

      // Fetch CEO name if available
      String? ceoName;
      if (ceoId != null) {
        try {
          final ceoResponse = await _esi.get('/characters/$ceoId/');
          final ceoData = (ceoResponse.data is String
                  ? jsonDecode(ceoResponse.data as String)
                  : ceoResponse.data)
              as Map<String, dynamic>;
          ceoName = ceoData['name'] as String?;
        } catch (_) {
          // Ignore CEO fetch failures
        }
      }

      // Fetch alliance name if available
      String? allianceName;
      if (allianceId != null) {
        try {
          final allianceResponse = await _esi.get('/alliances/$allianceId/');
          final allianceData = (allianceResponse.data is String
                  ? jsonDecode(allianceResponse.data as String)
                  : allianceResponse.data)
              as Map<String, dynamic>;
          allianceName = allianceData['name'] as String?;
        } catch (_) {
          // Ignore alliance fetch failures
        }
      }

      final corporation = Corporation(
        id: corporationId,
        name: name,
        ticker: ticker,
        ceoId: ceoId,
        ceoName: ceoName,
        allianceId: allianceId,
        allianceName: allianceName,
        addedAt: DateTime.now(),
      );

      await _db.into(_db.corporations).insertOnConflictUpdate(
            CorporationsCompanion.insert(
              id: Value(corporationId),
              name: name,
              ticker: Value(ticker),
              ceoId: Value(ceoId),
              ceoName: Value(ceoName),
              allianceId: Value(allianceId),
              allianceName: Value(allianceName),
              addedAt: DateTime.now(),
            ),
          );

      return corporation;
    } catch (e) {
      // Return null on failure (e.g., invalid corporation ID)
      return null;
    }
  }

  /// Gets a corporation by ID from the local database.
  Future<Corporation?> getCorporation(int corporationId) async {
    return await (_db.select(_db.corporations)
          ..where((c) => c.id.equals(corporationId)))
        .getSingleOrNull();
  }

  /// Gets all corporations from the local database.
  Future<List<Corporation>> getAllCorporations() async {
    return await _db.select(_db.corporations).get();
  }

  /// Deletes a corporation from the local database.
  Future<void> deleteCorporation(int corporationId) async {
    await (_db.delete(_db.corporations)..where((c) => c.id.equals(corporationId)))
        .go();
  }

  /// Updates corporation name for a character (populates the corporationName field).
  Future<void> updateCharacterCorporationName(
    int characterId,
    int corporationId,
  ) async {
    final corp = await getCorporation(corporationId);
    if (corp != null) {
      await (_db.update(_db.characters)..where((c) => c.id.equals(characterId)))
          .write(CharactersCompanion(
        corporationName: Value(corp.name),
      ));
    }
  }
}
