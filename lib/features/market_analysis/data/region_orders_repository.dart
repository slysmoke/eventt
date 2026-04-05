import 'dart:math' as math;

import '../../../core/esi/esi_client.dart';
import '../../market_browser/data/market_order_repository.dart';

/// Fetches ALL sell orders for a region using ESI pagination.
///
/// The ESI `/markets/{region_id}/orders/` endpoint returns up to 1000 orders
/// per page. The total page count is given by the `X-Pages` response header.
class RegionOrdersRepository {
  final EsiClient _esi;

  const RegionOrdersRepository({required EsiClient esi}) : _esi = esi;

  /// Fetches all sell orders for [regionId].
  ///
  /// [onProgress] is called after each page batch with (donePage, totalPages).
  Future<List<MarketOrder>> fetchAllSellOrders(
    int regionId, {
    void Function(int done, int total)? onProgress,
  }) async {
    final firstResp = await _esi.get(
      '/markets/$regionId/orders/',
      queryParameters: {'order_type': 'sell', 'page': 1},
    );

    if (firstResp.statusCode == null || firstResp.statusCode! >= 400) {
      return [];
    }

    final result = _parse(firstResp.data);

    final totalPages =
        int.tryParse(firstResp.headers.value('x-pages') ?? '1') ?? 1;
    onProgress?.call(1, totalPages);

    if (totalPages > 1) {
      const batchSize = 8;
      for (var page = 2; page <= totalPages; page += batchSize) {
        final end = math.min(page + batchSize - 1, totalPages);
        final futures = List.generate(
          end - page + 1,
          (i) => _esi.get(
            '/markets/$regionId/orders/',
            queryParameters: {'order_type': 'sell', 'page': page + i},
          ),
        );
        final responses = await Future.wait(futures);
        for (final r in responses) {
          if (r.statusCode != null && r.statusCode! < 400) {
            result.addAll(_parse(r.data));
          }
        }
        onProgress?.call(end, totalPages);
      }
    }

    return result;
  }

  static List<MarketOrder> _parse(dynamic data) {
    final raw = data is List
        ? data.cast<Map<String, dynamic>>()
        : <Map<String, dynamic>>[];
    return raw.map(MarketOrder.fromJson).toList();
  }
}
