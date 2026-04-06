import 'dart:convert';

import 'package:dio/dio.dart' show Response;

import '../../../core/esi/esi_client.dart';
import '../../../core/sde/sde_database.dart' show SdeDatabase;

/// One of the active character's market orders.
class CharacterOrder {
  final int orderId;
  final bool isBuyOrder;
  final double price;
  final int volumeRemain;
  final int volumeTotal;
  final int locationId;
  final int typeId;
  final String? typeName;
  final DateTime issued;
  final int duration;
  final String range;
  final double? escrow;
  final double? feePaid;
  final int minVolume;

  const CharacterOrder({
    required this.orderId,
    required this.isBuyOrder,
    required this.price,
    required this.volumeRemain,
    required this.volumeTotal,
    required this.locationId,
    required this.typeId,
    this.typeName,
    required this.issued,
    required this.duration,
    required this.range,
    this.escrow,
    this.feePaid,
    required this.minVolume,
  });

  factory CharacterOrder.fromJson(Map<String, dynamic> json) => CharacterOrder(
        orderId: json['order_id'] as int,
        isBuyOrder: json['is_buy_order'] as bool,
        price: (json['price'] as num).toDouble(),
        volumeRemain: json['volume_remain'] as int,
        volumeTotal: json['volume_total'] as int,
        locationId: json['location_id'] as int,
        typeId: json['type_id'] as int,
        issued: DateTime.parse(json['issued'] as String),
        duration: json['duration'] as int,
        range: json['range'] as String,
        escrow: (json['escrow'] as num?)?.toDouble(),
        feePaid: (json['fee_paid'] as num?)?.toDouble(),
        minVolume: json['min_volume'] as int,
      );

  CharacterOrder copyWith({String? typeName}) => CharacterOrder(
        orderId: orderId,
        isBuyOrder: isBuyOrder,
        price: price,
        volumeRemain: volumeRemain,
        volumeTotal: volumeTotal,
        locationId: locationId,
        typeId: typeId,
        typeName: typeName ?? this.typeName,
        issued: issued,
        duration: duration,
        range: range,
        escrow: escrow,
        feePaid: feePaid,
        minVolume: minVolume,
      );
}

/// Fetches the active character's market orders from ESI.
class CharacterOrderRepository {
  final EsiClient _esi;
  final SdeDatabase? _sde;

  const CharacterOrderRepository({
    required EsiClient esi,
    SdeDatabase? sde,
  })  : _esi = esi,
        _sde = sde;

  /// Fetches all open orders for [characterId].
  /// Resolves type names from SDE database.
  Future<List<CharacterOrder>> fetchOrders({
    required int characterId,
    required String accessToken,
  }) async {
    final response = await _esi.get(
      '/characters/$characterId/orders/',
      accessToken: accessToken,
    );

    if (response.statusCode == null || response.statusCode! >= 400) {
      return [];
    }

    final data = response.data;
    final list = data is List ? data.cast<Map<String, dynamic>>() : <Map<String, dynamic>>[];
    final orders = list.map(CharacterOrder.fromJson).toList();

    // Resolve type names from SDE
    final typeIds = orders.map((o) => o.typeId).toSet().toList();
    if (_sde != null) {
      final typeMap = _sde!.getTypesByIds(typeIds);
      return orders
          .map((o) => o.copyWith(
                typeName: typeMap[o.typeId]?.typeName,
              ))
          .toList();
    }

    return orders;
  }

  /// Fetches order history (expired/filled orders) for [characterId].
  Future<List<CharacterOrder>> fetchOrderHistory({
    required int characterId,
    required String accessToken,
  }) async {
    final response = await _esi.get(
      '/characters/$characterId/orders/history/',
      accessToken: accessToken,
    );

    if (response.statusCode == null || response.statusCode! >= 400) {
      return [];
    }

    final data = response.data;
    final list = data is List ? data.cast<Map<String, dynamic>>() : <Map<String, dynamic>>[];
    return list.map(CharacterOrder.fromJson).toList();
  }
}
