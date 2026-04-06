import 'dart:convert';

import 'package:dio/dio.dart' show Response;

import '../../../core/database/app_database.dart';
import '../../../core/esi/esi_client.dart';

/// One asset (item) belonging to a character.
class Asset {
  final int itemId;
  final int typeId;
  final String? typeName;
  final int locationId;
  final String? locationName;
  final int quantity;
  final bool isBlueprintCopy;

  const Asset({
    required this.itemId,
    required this.typeId,
    this.typeName,
    required this.locationId,
    this.locationName,
    required this.quantity,
    this.isBlueprintCopy = false,
  });

  factory Asset.fromJson(Map<String, dynamic> json) {
    final rawQuantity = json['quantity'] as int;
    // Blueprint copies may have negative quantity — treat as 1
    final quantity = rawQuantity < 0 ? 1 : rawQuantity;

    return Asset(
      itemId: json['item_id'] as int,
      typeId: json['type_id'] as int,
      locationId: json['location_id'] as int,
      quantity: quantity,
      isBlueprintCopy: json['is_blueprint_copy'] as bool? ?? false,
    );
  }

  Asset copyWith({String? typeName, String? locationName}) => Asset(
        itemId: itemId,
        typeId: typeId,
        typeName: typeName ?? this.typeName,
        locationId: locationId,
        locationName: locationName ?? this.locationName,
        quantity: quantity,
        isBlueprintCopy: isBlueprintCopy,
      );
}

/// Fetches the active character's assets from ESI.
class AssetRepository {
  final EsiClient _esi;
  final AppDatabase _db;

  const AssetRepository({
    required EsiClient esi,
    required AppDatabase db,
  })  : _esi = esi,
        _db = db;

  /// Fetches all assets for [characterId].
  /// Resolves type names from SDE and location names via ESI universe/names.
  Future<List<Asset>> fetchAssets({
    required int characterId,
    required String accessToken,
  }) async {
    final allAssets = <Asset>[];
    String? pageToken;

    // Paginated fetch
    do {
      final response = await _esi.get(
        '/characters/$characterId/assets/',
        accessToken: accessToken,
        queryParameters: pageToken != null ? {'page': pageToken} : null,
      );

      if (response.statusCode == null || response.statusCode! >= 400) break;

      final data = response.data;
      final list = data is List ? data.cast<Map<String, dynamic>>() : <Map<String, dynamic>>[];
      allAssets.addAll(list.map(Asset.fromJson));

      // Check for next page
      final pagesHeader = response.headers.map['x-pages'];
      if (pagesHeader != null && pagesHeader.isNotEmpty) {
        final totalPages = int.tryParse(pagesHeader.first) ?? 1;
        final currentPage = int.tryParse(pageToken ?? '1') ?? 1;
        if (currentPage < totalPages) {
          pageToken = (currentPage + 1).toString();
        } else {
          pageToken = null;
        }
      } else {
        pageToken = null;
      }
    } while (pageToken != null);

    // Resolve type names from SDE
    final typeIds = allAssets.map((a) => a.typeId).toSet().toList();
    final sde = _db.sdeDatabase;
    Map<int, String> typeNames = {};
    if (sde != null) {
      final typeMap = sde.getTypesByIds(typeIds);
      typeNames = {for (final entry in typeMap.entries) entry.key: entry.value.typeName};
    }

    // Resolve location names for non-station locations
    final locationIds = allAssets
        .map((a) => a.locationId)
        .where((id) => !_isStationId(id))
        .toSet()
        .toList();

    Map<int, String> locationNames = {};
    if (locationIds.isNotEmpty) {
      locationNames = await _resolveLocationNames(locationIds, accessToken);
    }

    return allAssets
        .map((a) => a.copyWith(
              typeName: typeNames[a.typeId],
              locationName: locationNames[a.locationId],
            ))
        .toList();
  }

  /// Checks if a location ID is a station (has SDE data).
  bool _isStationId(int id) {
    // CCP stations: 60000001-61000000 or 66000000-66014933 (offset by -6000001)
    return (id >= 60000001 && id <= 61000000) ||
        (id >= 66000000 && id <= 66014933);
  }

  /// Resolves location names via ESI /universe/names/ endpoint.
  Future<Map<int, String>> _resolveLocationNames(
    List<int> locationIds,
    String accessToken,
  ) async {
    final result = <int, String>{};

    // Batch in chunks of 1000 (ESI limit)
    for (var i = 0; i < locationIds.length; i += 1000) {
      final chunk = locationIds.skip(i).take(1000).toList();
      try {
        final response = await _esi.post(
          '/universe/names/',
          data: chunk,
          accessToken: accessToken,
        );

        if (response.statusCode == 200 && response.data is List) {
          for (final item in response.data as List) {
            final entry = item as Map<String, dynamic>;
            final id = entry['id'] as int?;
            final name = entry['name'] as String?;
            if (id != null && name != null) {
              result[id] = name;
            }
          }
        }
      } catch (_) {
        // Ignore resolution failures
      }
    }

    return result;
  }
}
