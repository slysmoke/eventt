import '../../../core/esi/esi_client.dart';
import '../../../core/sde/sde_database.dart' show LocationHierarchy, SdeDatabase;

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

/// Fetches the active character's assets from ESI.
class AssetRepository {
  final EsiClient _esi;
  final SdeDatabase? _sde;

  const AssetRepository({
    required EsiClient esi,
    SdeDatabase? sde,
  })  : _esi = esi,
        _sde = sde;

  /// Fetches all assets for [characterId].
  /// Resolves type names and location hierarchy from SDE.
  /// Fetches prices from ESI /markets/prices/.
  Future<List<Asset>> fetchAssets({
    required int characterId,
    required String accessToken,
  }) async {
    final allAssets = <Asset>[];
    String? pageToken;

    // Paginated fetch from ESI
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
    Map<int, String> typeNames = {};
    if (_sde != null) {
      final typeMap = _sde!.getTypesByIds(typeIds);
      typeNames = {for (final entry in typeMap.entries) entry.key: entry.value.typeName};
    }

    // Resolve location hierarchy from SDE (region → system → station)
    Map<int, LocationHierarchy> locationHierarchies = {};
    if (_sde != null) {
      final locationIds = allAssets.map((a) => a.locationId).toSet().toList();
      locationHierarchies = _sde!.getLocationHierarchies(locationIds);
    }

    // Fetch market prices from ESI
    final priceMap = await _fetchPrices(accessToken);

    // For player structures (citadels) not in SDE, resolve names via ESI
    final unknownLocationIds = allAssets
        .map((a) => a.locationId)
        .where((id) => !locationHierarchies.containsKey(id))
        .toSet()
        .toList();
    final structureNames = unknownLocationIds.isNotEmpty
        ? await _resolveStructureNames(unknownLocationIds, accessToken)
        : <int, String>{};

    return allAssets.map((a) {
      final hier = locationHierarchies[a.locationId];
      final structureName = structureNames[a.locationId];

      return a.copyWith(
        typeName: typeNames[a.typeId],
        regionId: hier?.regionId,
        regionName: hier?.regionName,
        systemId: hier?.systemId,
        systemName: hier?.systemName,
        stationName: hier?.stationName ?? structureName,
        unitPrice: priceMap[a.typeId],
      );
    }).toList();
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

  /// Resolves player structure (citadel) names via ESI /universe/names/.
  Future<Map<int, String>> _resolveStructureNames(
    List<int> locationIds,
    String accessToken,
  ) async {
    final result = <int, String>{};

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
