import '../../../core/esi/esi_client.dart';

class MarketOrder {
  final int orderId;
  final bool isBuyOrder;
  final double price;
  final int volumeRemain;
  final int volumeTotal;
  final int locationId;
  final int typeId;

  const MarketOrder({
    required this.orderId,
    required this.isBuyOrder,
    required this.price,
    required this.volumeRemain,
    required this.volumeTotal,
    required this.locationId,
    required this.typeId,
  });

  factory MarketOrder.fromJson(Map<String, dynamic> json) => MarketOrder(
        orderId: json['order_id'] as int,
        isBuyOrder: json['is_buy_order'] as bool,
        price: (json['price'] as num).toDouble(),
        volumeRemain: json['volume_remain'] as int,
        volumeTotal: json['volume_total'] as int,
        locationId: json['location_id'] as int,
        typeId: json['type_id'] as int,
      );
}

/// Some items bypass the normal region market and trade in a dedicated ESI region.
/// Key: typeId → fixed regionId to use regardless of selected region.
const Map<int, int> fixedMarketRegions = {
  44992: 19000001, // PLEX — New Eden market
};

/// Fetches and sorts market orders from ESI for a given region + type.
class MarketOrderRepository {
  final EsiClient _esi;

  const MarketOrderRepository({required EsiClient esi}) : _esi = esi;

  /// Returns all orders for [typeId] in [regionId].
  /// For items with a fixed market region (e.g. PLEX), [regionId] is ignored.
  /// Sell orders are sorted ascending by price; buy orders descending.
  Future<List<MarketOrder>> fetchOrders(int regionId, int typeId) async {
    final effectiveRegion = fixedMarketRegions[typeId] ?? regionId;
    final response = await _esi.get(
      '/markets/$effectiveRegion/orders/',
      queryParameters: {'type_id': typeId, 'order_type': 'all'},
    );

    if (response.statusCode == null || response.statusCode! >= 400) {
      return [];
    }

    final raw = response.data;
    final list = raw is List ? raw.cast<Map<String, dynamic>>() : <Map<String, dynamic>>[];
    final orders = list.map(MarketOrder.fromJson).toList();

    orders.sort((a, b) {
      if (a.isBuyOrder != b.isBuyOrder) {
        return a.isBuyOrder ? 1 : -1; // sells first
      }
      return a.isBuyOrder
          ? b.price.compareTo(a.price) // buy: desc
          : a.price.compareTo(b.price); // sell: asc
    });

    return orders;
  }
}
