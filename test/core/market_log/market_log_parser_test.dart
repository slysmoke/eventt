import 'package:flutter_test/flutter_test.dart';

import 'package:eventt/core/market_log/market_log_parser.dart';

// Sample CSV content matching the real EVE export format.
// Header ends with a comma; each data row also ends with a trailing comma.
const _sampleCsv = '''price,volRemaining,typeID,range,orderID,volEntered,minVolume,bid,issueDate,duration,stationID,regionID,solarSystemID,jumps,

4920000.0,34.0,16278,32767,7104641828,34,1,False,2025-07-20 18:46:34.000,90,60008494,10000043,30002187,0,

4922000.0,32.0,16278,32767,7101669300,100,1,False,2025-07-20 18:01:40.000,90,60008494,10000043,30002187,0,

3613000.0,18.0,16278,-1,7089660694,50,1,True,2025-07-20 21:40:03.000,7,60008494,10000043,30002187,0,

3610.0,200.0,16278,32767,7104500725,200,1,True,2025-07-20 18:46:41.000,90,60008494,10000043,30002187,0,
''';

// Filename: Region-ItemName-Date Time.txt
const _filename = 'The Forge-Ice Harvester I-2025.07.20 220005.txt';

void main() {
  group('MarketLogParser.parse()', () {
    late MarketLogFile file;

    setUp(() {
      file = MarketLogParser.parse(content: _sampleCsv, filename: _filename);
    });

    test('extracts typeId from data rows', () {
      expect(file.typeId, 16278);
    });

    test('extracts regionId from data rows', () {
      expect(file.regionId, 10000043);
    });

    test('extracts region name from filename', () {
      expect(file.regionName, 'The Forge');
    });

    test('extracts item name from filename', () {
      expect(file.itemName, 'Ice Harvester I');
    });

    test('parses exportedAt from filename', () {
      expect(file.exportedAt?.year, 2025);
      expect(file.exportedAt?.month, 7);
      expect(file.exportedAt?.day, 20);
    });

    test('parses all non-empty data rows', () {
      expect(file.orders.length, 4);
    });

    test('correctly identifies sell orders (bid=False)', () {
      final sells = file.orders.where((o) => !o.isBuyOrder).toList();
      expect(sells.length, 2);
    });

    test('correctly identifies buy orders (bid=True)', () {
      final buys = file.orders.where((o) => o.isBuyOrder).toList();
      expect(buys.length, 2);
    });

    test('parses price correctly', () {
      final firstSell = file.orders
          .where((o) => !o.isBuyOrder)
          .first;
      expect(firstSell.price, closeTo(4920000.0, 1.0));
    });

    test('parses volRemaining as int', () {
      final firstSell = file.orders
          .where((o) => !o.isBuyOrder)
          .first;
      expect(firstSell.volumeRemain, 34);
    });

    test('parses orderId correctly', () {
      final order = file.orders.firstWhere((o) => o.orderId == 7104641828);
      expect(order.orderId, 7104641828);
    });

    test('parses locationId (stationId) correctly', () {
      expect(file.orders.first.locationId, 60008494);
    });

    test('skips blank lines', () {
      // The sample CSV has blank lines between rows; still 4 orders
      expect(file.orders.length, 4);
    });
  });

  group('MarketLogParser.parse() — edge cases', () {
    test('returns empty orders for header-only content', () {
      const headerOnly =
          'price,volRemaining,typeID,range,orderID,volEntered,minVolume,bid,issueDate,duration,stationID,regionID,solarSystemID,jumps,\n';
      final file = MarketLogParser.parse(
          content: headerOnly, filename: _filename);
      expect(file.orders, isEmpty);
      expect(file.typeId, isNull);
    });

    test('filename with multiple hyphens in region name is handled', () {
      // e.g. "Stain-Item Name-2025.01.01 000000.txt"
      const fn = 'Stain-Tritanium-2025.01.01 120000.txt';
      final file = MarketLogParser.parse(content: _sampleCsv, filename: fn);
      expect(file.regionName, 'Stain');
      expect(file.itemName, 'Tritanium');
    });

    test('filename with spaces in item name', () {
      const fn = 'The Forge-Skill Injector-2025.03.15 093000.txt';
      final file = MarketLogParser.parse(content: _sampleCsv, filename: fn);
      expect(file.regionName, 'The Forge');
      expect(file.itemName, 'Skill Injector');
    });
  });

  group('MarketLogFile.bestSellPrice / bestBuyPrice', () {
    test('bestSellPrice returns lowest sell price', () {
      final file =
          MarketLogParser.parse(content: _sampleCsv, filename: _filename);
      expect(file.bestSellPrice, closeTo(4920000.0, 1.0));
    });

    test('bestBuyPrice returns highest buy price', () {
      final file =
          MarketLogParser.parse(content: _sampleCsv, filename: _filename);
      expect(file.bestBuyPrice, closeTo(3613000.0, 1.0));
    });

    test('bestSellPrice is null when no sell orders', () {
      const buyOnly = '''price,volRemaining,typeID,range,orderID,volEntered,minVolume,bid,issueDate,duration,stationID,regionID,solarSystemID,jumps,
3613000.0,18.0,16278,-1,7089660694,50,1,True,2025-07-20 21:40:03.000,7,60008494,10000043,30002187,0,
''';
      final file =
          MarketLogParser.parse(content: buyOnly, filename: _filename);
      expect(file.bestSellPrice, isNull);
    });

    test('bestBuyPrice is null when no buy orders', () {
      const sellOnly = '''price,volRemaining,typeID,range,orderID,volEntered,minVolume,bid,issueDate,duration,stationID,regionID,solarSystemID,jumps,
4920000.0,34.0,16278,32767,7104641828,34,1,False,2025-07-20 18:46:34.000,90,60008494,10000043,30002187,0,
''';
      final file =
          MarketLogParser.parse(content: sellOnly, filename: _filename);
      expect(file.bestBuyPrice, isNull);
    });
  });
}
