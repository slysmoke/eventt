import '../../../core/esi/esi_client.dart';
import '../../../features/market_browser/data/market_order_repository.dart'
    show fixedMarketRegions;
import 'market_history_entry.dart';

/// Fetches historical market data from ESI for a given region + type.
class MarketHistoryRepository {
  final EsiClient _esi;

  const MarketHistoryRepository({required EsiClient esi}) : _esi = esi;

  /// Returns daily history for [typeId] in [regionId], sorted by date ascending.
  /// For items with a fixed market region (e.g. PLEX), [regionId] is ignored.
  Future<List<MarketHistoryEntry>> fetchHistory(
      int regionId, int typeId) async {
    final effectiveRegion = fixedMarketRegions[typeId] ?? regionId;
    final response = await _esi.get(
      '/markets/$effectiveRegion/history/',
      queryParameters: {'type_id': typeId},
    );

    if (response.statusCode == null || response.statusCode! >= 400) {
      return [];
    }

    final raw = response.data;
    final list = raw is List
        ? raw.cast<Map<String, dynamic>>()
        : <Map<String, dynamic>>[];

    final entries = list.map(MarketHistoryEntry.fromJson).toList();
    entries.sort((a, b) => a.date.compareTo(b.date));
    return entries;
  }
}
