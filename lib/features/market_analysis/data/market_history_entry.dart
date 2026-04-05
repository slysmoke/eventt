class MarketHistoryEntry {
  final DateTime date;
  final double average;
  final double highest;
  final double lowest;
  final int orderCount;
  final int volume;

  const MarketHistoryEntry({
    required this.date,
    required this.average,
    required this.highest,
    required this.lowest,
    required this.orderCount,
    required this.volume,
  });

  factory MarketHistoryEntry.fromJson(Map<String, dynamic> json) =>
      MarketHistoryEntry(
        date: DateTime.parse('${json['date'] as String}T00:00:00Z'),
        average: (json['average'] as num).toDouble(),
        highest: (json['highest'] as num).toDouble(),
        lowest: (json['lowest'] as num).toDouble(),
        orderCount: json['order_count'] as int,
        volume: (json['volume'] as num).toInt(),
      );
}
