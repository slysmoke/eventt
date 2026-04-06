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
  final int quantity;
  final bool isBlueprintCopy;

  // Resolved location hierarchy
  final int? regionId;
  final String? regionName;
  final int? systemId;
  final String? systemName;
  final String? stationName;

  // Price data
  final double? unitPrice;
  double get totalPrice => unitPrice != null ? unitPrice! * quantity : 0;

  const Asset({
    required this.itemId,
    required this.typeId,
    this.typeName,
    required this.locationId,
    required this.quantity,
    this.isBlueprintCopy = false,
    this.regionId,
    this.regionName,
    this.systemId,
    this.systemName,
    this.stationName,
    this.unitPrice,
  });

  factory Asset.fromJson(Map<String, dynamic> json) {
    final rawQuantity = json['quantity'] as int;
    final quantity = rawQuantity < 0 ? 1 : rawQuantity;

    return Asset(
      itemId: json['item_id'] as int,
      typeId: json['type_id'] as int,
      locationId: json['location_id'] as int,
      quantity: quantity,
      isBlueprintCopy: json['is_blueprint_copy'] as bool? ?? false,
    );
  }

  Asset copyWith({
    String? typeName,
    int? regionId,
    String? regionName,
    int? systemId,
    String? systemName,
    String? stationName,
    double? unitPrice,
  }) =>
      Asset(
        itemId: itemId,
        typeId: typeId,
        typeName: typeName ?? this.typeName,
        locationId: locationId,
        quantity: quantity,
        isBlueprintCopy: isBlueprintCopy,
        regionId: regionId ?? this.regionId,
        regionName: regionName ?? this.regionName,
        systemId: systemId ?? this.systemId,
        systemName: systemName ?? this.systemName,
        stationName: stationName ?? this.stationName,
        unitPrice: unitPrice ?? this.unitPrice,
      );
}

/// Location info resolved from ESI.
class _LocationInfo {
  final int? regionId;
  final String? regionName;
  final int? systemId;
  final String? systemName;
  final String? stationName;

  const _LocationInfo({
    this.regionId,
    this.regionName,
    this.systemId,
    this.systemName,
    this.stationName,
  });
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
  /// Resolves type names, location hierarchy, and prices.
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

    // Fetch market prices
    final priceMap = await _fetchPrices(accessToken);

    // Resolve location hierarchy (region → system → station)
    final locationIds = allAssets.map((a) => a.locationId).toSet().toList();
    final locationInfo = await _resolveLocations(locationIds, accessToken);

    return allAssets
        .map((a) {
          final loc = locationInfo[a.locationId];
          return a.copyWith(
            typeName: typeNames[a.typeId],
            regionId: loc?.regionId,
            regionName: loc?.regionName,
            systemId: loc?.systemId,
            systemName: loc?.systemName,
            stationName: loc?.stationName,
            unitPrice: priceMap[a.typeId],
          );
        })
        .toList();
  }

  /// Fetches current market prices from ESI.
  Future<Map<int, double>> _fetchPrices(String accessToken) async {
    try {
      final response = await _esi.get(
        '/markets/prices/',
        accessToken: accessToken,
      );

      if (response.statusCode == 200 && response.data is List) {
        final prices = <int, double>{};
        for (final item in response.data as List) {
          final entry = item as Map<String, dynamic>;
          final typeId = entry['type_id'] as int?;
          final avgPrice = (entry['average_price'] as num?)?.toDouble();
          if (typeId != null && avgPrice != null) {
            prices[typeId] = avgPrice;
          }
        }
        return prices;
      }
    } catch (_) {
      // Ignore price fetch failures
    }
    return {};
  }

  /// Resolves location hierarchy: locationId → region → system → station.
  Future<Map<int, _LocationInfo>> _resolveLocations(
    List<int> locationIds,
    String accessToken,
  ) async {
    final result = <int, _LocationInfo>{};

    // Batch resolve all location IDs via /universe/names/
    final namesMap = await _resolveNames(locationIds, accessToken);

    // For each location, determine category and resolve parent
    for (final locationId in locationIds) {
      final nameEntry = namesMap[locationId];
      if (nameEntry == null) continue;

      final category = nameEntry['category'] as String?;
      final name = nameEntry['name'] as String?;

      if (category == 'solar_system') {
        // System-level asset — need to find its region
        final regionInfo = await _getSystemRegion(locationId, accessToken);
        result[locationId] = _LocationInfo(
          regionId: regionInfo?['region_id'] as int?,
          regionName: regionInfo?['region_name'] as String?,
          systemId: locationId,
          systemName: name,
        );
      } else if (category == 'station' || category == 'structure') {
        // Station-level asset — need system and region
        final locationInfo = await _getStationInfo(locationId, accessToken);
        result[locationId] = _LocationInfo(
          regionId: locationInfo?['region_id'] as int?,
          regionName: locationInfo?['region_name'] as String?,
          systemId: locationInfo?['system_id'] as int?,
          systemName: locationInfo?['system_name'] as String?,
          stationName: name,
        );
      } else {
        // Unknown category (e.g., citadel, item) — try as station
        final locationInfo = await _getStationInfo(locationId, accessToken);
        if (locationInfo != null) {
          result[locationId] = _LocationInfo(
            regionId: locationInfo['region_id'] as int?,
            regionName: locationInfo['region_name'] as String?,
            systemId: locationInfo['system_id'] as int?,
            systemName: locationInfo['system_name'] as String?,
            stationName: locationInfo['station_name'] as String? ?? name,
          );
        } else {
          result[locationId] = _LocationInfo(stationName: name);
        }
      }
    }

    return result;
  }

  /// Resolves names for a list of IDs via /universe/names/.
  Future<Map<int, Map<String, dynamic>>> _resolveNames(
    List<int> ids,
    String accessToken,
  ) async {
    final result = <int, Map<String, dynamic>>{};

    for (var i = 0; i < ids.length; i += 1000) {
      final chunk = ids.skip(i).take(1000).toList();
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
            if (id != null) {
              result[id] = entry;
            }
          }
        }
      } catch (_) {}
    }

    return result;
  }

  /// Gets region info for a solar system via /universe/systems/{id}/.
  Future<Map<String, dynamic>?> _getSystemRegion(
    int systemId,
    String accessToken,
  ) async {
    try {
      final response = await _esi.get(
        '/universe/systems/$systemId/',
        accessToken: accessToken,
      );

      if (response.statusCode == 200 && response.data is Map) {
        final data = response.data as Map<String, dynamic>;
        final constellationId = data['constellation_id'] as int?;

        if (constellationId != null) {
          final constResponse = await _esi.get(
            '/universe/constellations/$constellationId/',
            accessToken: accessToken,
          );

          if (constResponse.statusCode == 200 && constResponse.data is Map) {
            final constData = constResponse.data as Map<String, dynamic>;
            final regionId = constData['region_id'] as int?;

            if (regionId != null) {
              final regResponse = await _esi.get(
                '/universe/regions/$regionId/',
                accessToken: accessToken,
              );

              if (regResponse.statusCode == 200 && regResponse.data is Map) {
                final regData = regResponse.data as Map<String, dynamic>;
                return {
                  'region_id': regionId,
                  'region_name': regData['name'] as String?,
                };
              }
            }
          }
        }
      }
    } catch (_) {}
    return null;
  }

  /// Gets station/structure info including system and region.
  Future<Map<String, dynamic>?> _getStationInfo(
    int locationId,
    String accessToken,
  ) async {
    // Try as CCP station first
    try {
      final response = await _esi.get(
        '/universe/stations/$locationId/',
        accessToken: accessToken,
      );

      if (response.statusCode == 200 && response.data is Map) {
        final data = response.data as Map<String, dynamic>;
        final systemId = data['system_id'] as int?;
        final regionId = data['region_id'] as int?;

        String? systemName;
        String? regionName;

        if (systemId != null) {
          final sysResponse = await _esi.get(
            '/universe/systems/$systemId/',
            accessToken: accessToken,
          );
          if (sysResponse.statusCode == 200 && sysResponse.data is Map) {
            systemName = (sysResponse.data as Map<String, dynamic>)['name'] as String?;
          }
        }

        if (regionId != null) {
          final regResponse = await _esi.get(
            '/universe/regions/$regionId/',
            accessToken: accessToken,
          );
          if (regResponse.statusCode == 200 && regResponse.data is Map) {
            regionName = (regResponse.data as Map<String, dynamic>)['name'] as String?;
          }
        }

        return {
          'system_id': systemId,
          'system_name': systemName,
          'region_id': regionId,
          'region_name': regionName,
          'station_name': data['name'] as String?,
        };
      }
    } catch (_) {}

    // Try as player structure
    try {
      final response = await _esi.get(
        '/universe/structures/$locationId/',
        accessToken: accessToken,
      );

      if (response.statusCode == 200 && response.data is Map) {
        final data = response.data as Map<String, dynamic>;
        final systemId = data['solar_system_id'] as int?;

        String? systemName;
        String? regionName;
        int? regionId;

        if (systemId != null) {
          final sysResponse = await _esi.get(
            '/universe/systems/$systemId/',
            accessToken: accessToken,
          );
          if (sysResponse.statusCode == 200 && sysResponse.data is Map) {
            systemName = (sysResponse.data as Map<String, dynamic>)['name'] as String?;
            final constellationId = (sysResponse.data as Map<String, dynamic>)['constellation_id'] as int?;

            if (constellationId != null) {
              final constResponse = await _esi.get(
                '/universe/constellations/$constellationId/',
                accessToken: accessToken,
              );
              if (constResponse.statusCode == 200 && constResponse.data is Map) {
                regionId = (constResponse.data as Map<String, dynamic>)['region_id'] as int?;

                if (regionId != null) {
                  final regResponse = await _esi.get(
                    '/universe/regions/$regionId/',
                    accessToken: accessToken,
                  );
                  if (regResponse.statusCode == 200 && regResponse.data is Map) {
                    regionName = (regResponse.data as Map<String, dynamic>)['name'] as String?;
                  }
                }
              }
            }
          }
        }

        return {
          'system_id': systemId,
          'system_name': systemName,
          'region_id': regionId,
          'region_name': regionName,
          'station_name': data['name'] as String?,
        };
      }
    } catch (_) {}

    return null;
  }
}
