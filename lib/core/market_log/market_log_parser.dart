import '../../features/market_browser/data/market_order_repository.dart';

export '../../features/market_browser/data/market_order_repository.dart'
    show MarketOrder;

/// Parsed EVE Online market log file.
class MarketLogFile {
  /// Item type ID extracted from the data rows (null if file has no data rows).
  final int? typeId;

  /// Region ID extracted from the data rows (null if no data rows).
  final int? regionId;

  /// Region name extracted from the filename (e.g. "The Forge").
  final String regionName;

  /// Item name extracted from the filename (e.g. "Ice Harvester I").
  final String itemName;

  /// Export timestamp extracted from the filename.
  final DateTime? exportedAt;

  /// All parsed orders.
  final List<MarketOrder> orders;

  const MarketLogFile({
    required this.typeId,
    required this.regionId,
    required this.regionName,
    required this.itemName,
    required this.exportedAt,
    required this.orders,
  });

  /// Lowest sell price among all sell orders, or null if none exist.
  double? get bestSellPrice {
    final sells = orders.where((o) => !o.isBuyOrder);
    if (sells.isEmpty) return null;
    return sells.map((o) => o.price).reduce((a, b) => a < b ? a : b);
  }

  /// Highest buy price among all buy orders, or null if none exist.
  double? get bestBuyPrice {
    final buys = orders.where((o) => o.isBuyOrder);
    if (buys.isEmpty) return null;
    return buys.map((o) => o.price).reduce((a, b) => a > b ? a : b);
  }
}

/// Parses EVE Online market export CSV files.
///
/// Filename format: `Region-ItemName-YYYY.MM.DD HHMMSS.txt`
/// CSV columns: price,volRemaining,typeID,range,orderID,volEntered,
///              minVolume,bid,issueDate,duration,stationID,regionID,
///              solarSystemID,jumps,
class MarketLogParser {
  MarketLogParser._();

  /// Parses [content] (the file text) and [filename] (basename only).
  static MarketLogFile parse({
    required String content,
    required String filename,
  }) {
    final (regionName, itemName, exportedAt) = _parseFilename(filename);

    final lines = content.split('\n');
    if (lines.isEmpty) {
      return MarketLogFile(
        typeId: null,
        regionId: null,
        regionName: regionName,
        itemName: itemName,
        exportedAt: exportedAt,
        orders: const [],
      );
    }

    // First non-empty line is the header
    final headerLine = lines.firstWhere((l) => l.trim().isNotEmpty,
        orElse: () => '');
    if (headerLine.isEmpty) {
      return MarketLogFile(
        typeId: null,
        regionId: null,
        regionName: regionName,
        itemName: itemName,
        exportedAt: exportedAt,
        orders: const [],
      );
    }

    // Build column index map
    final headers = headerLine.split(',').map((h) => h.trim()).toList();
    final idx = {for (var i = 0; i < headers.length; i++) headers[i]: i};

    final priceIdx = idx['price']!;
    final volRemIdx = idx['volRemaining']!;
    final typeIdx = idx['typeID']!;
    final orderIdx = idx['orderID']!;
    final volEnIdx = idx['volEntered']!;
    final bidIdx = idx['bid']!;
    final stationIdx = idx['stationID']!;
    final regionIdx = idx['regionID']!;

    final orders = <MarketOrder>[];
    int? typeId;
    int? regionId;

    // Skip header line, parse data
    final dataLines = lines.skip(1);
    for (final line in dataLines) {
      final trimmed = line.trim();
      if (trimmed.isEmpty) continue;

      // Trailing comma means last field is empty — split still works
      final fields = trimmed.split(',');
      if (fields.length <= bidIdx) continue;

      final parsedTypeId = int.tryParse(fields[typeIdx].trim());
      final parsedRegionId = int.tryParse(fields[regionIdx].trim());
      final orderId = int.tryParse(fields[orderIdx].trim());
      final price = double.tryParse(fields[priceIdx].trim());
      final volRem = double.tryParse(fields[volRemIdx].trim())?.toInt();
      final volTotal = int.tryParse(fields[volEnIdx].trim());
      final stationId = int.tryParse(fields[stationIdx].trim());

      if (parsedTypeId == null ||
          orderId == null ||
          price == null ||
          volRem == null ||
          volTotal == null ||
          stationId == null) continue;

      typeId ??= parsedTypeId;
      regionId ??= parsedRegionId;

      final isBuyOrder = fields[bidIdx].trim() == 'True';

      orders.add(MarketOrder(
        orderId: orderId,
        isBuyOrder: isBuyOrder,
        price: price,
        volumeRemain: volRem,
        volumeTotal: volTotal,
        locationId: stationId,
        typeId: parsedTypeId,
      ));
    }

    return MarketLogFile(
      typeId: typeId,
      regionId: regionId,
      regionName: regionName,
      itemName: itemName,
      exportedAt: exportedAt,
      orders: orders,
    );
  }

  /// Parses filename → (regionName, itemName, exportedAt).
  ///
  /// Format: `Region-ItemName-YYYY.MM.DD HHMMSS.txt`
  ///
  /// The timestamp part matches `\d{4}\.\d{2}\.\d{2} \d{6}`.
  /// Everything before the last two hyphens (the one separating the timestamp)
  /// is split: first segment = region, rest = item name.
  static (String, String, DateTime?) _parseFilename(String filename) {
    // Strip .txt extension
    var name = filename;
    if (name.toLowerCase().endsWith('.txt')) {
      name = name.substring(0, name.length - 4);
    }

    // Find the timestamp: "YYYY.MM.DD HHMMSS" — look for the hyphen before it
    // Pattern: ends with "-YYYY.MM.DD HHMMSS"
    final tsPattern = RegExp(r'-(\d{4}\.\d{2}\.\d{2} \d{6})$');
    final tsMatch = tsPattern.firstMatch(name);

    DateTime? exportedAt;
    if (tsMatch != null) {
      final ts = tsMatch.group(1)!; // "2025.07.20 220005"
      exportedAt = _parseTimestamp(ts);
      name = name.substring(0, tsMatch.start);
    }

    // Now name is "Region-ItemName"
    // Split on first hyphen → region; rest → item name
    final firstHyphen = name.indexOf('-');
    String regionName;
    String itemName;
    if (firstHyphen < 0) {
      regionName = name;
      itemName = '';
    } else {
      regionName = name.substring(0, firstHyphen);
      itemName = name.substring(firstHyphen + 1);
    }

    return (regionName, itemName, exportedAt);
  }

  static DateTime? _parseTimestamp(String ts) {
    // "2025.07.20 220005"
    try {
      final datePart = ts.substring(0, 10).replaceAll('.', '-');
      final timePart = ts.substring(11); // "220005"
      final h = timePart.substring(0, 2);
      final m = timePart.substring(2, 4);
      final s = timePart.substring(4, 6);
      return DateTime.parse('${datePart}T$h:$m:${s}Z');
    } catch (_) {
      return null;
    }
  }
}
