import 'package:flutter_test/flutter_test.dart';

import 'package:eventt/core/sde/sde_models.dart';
import 'package:eventt/features/market_browser/data/market_order_repository.dart';
import 'package:eventt/features/market_analysis/domain/region_analysis_computer.dart';
import 'package:eventt/features/market_analysis/domain/import_params.dart';

void main() {
  final typeInfo = {
    34: const InvType(typeId: 34, typeName: 'Tritanium', volume: 0.01),
    35: const InvType(typeId: 35, typeName: 'Pyerite', volume: 0.02),
  };

  group('RegionAnalysisComputer.compute()', () {
    test('returns rows with correct margin calculation', () {
      // Buy price=80, Sell price=120 → diff=40, margin=33.33%
      final rows = RegionAnalysisComputer.compute(
        orders: [
          _buy(34, 80.0),
          _sell(34, 120.0),
        ],
        typeInfo: typeInfo,
        srcPriceType: PriceType.buy,
        dstPriceType: PriceType.sell,
      );

      expect(rows.length, 1);
      expect(rows.first.typeName, 'Tritanium');
      expect(rows.first.srcPrice, 80.0);
      expect(rows.first.dstPrice, 120.0);
      expect(rows.first.difference, closeTo(40.0, 0.001));
      expect(rows.first.margin, closeTo(33.333, 0.001));
    });

    test('uses min sell price for srcPriceType=sell', () {
      final rows = RegionAnalysisComputer.compute(
        orders: [
          _sell(34, 150.0),
          _sell(34, 100.0), // min
          _sell(34, 200.0),
        ],
        typeInfo: typeInfo,
        srcPriceType: PriceType.sell,
        dstPriceType: PriceType.sell,
      );

      expect(rows.first.srcPrice, 100.0);
    });

    test('uses max buy price for srcPriceType=buy', () {
      final rows = RegionAnalysisComputer.compute(
        orders: [
          _buy(34, 80.0),  // max
          _buy(34, 70.0),
          _buy(34, 60.0),
        ],
        typeInfo: typeInfo,
        srcPriceType: PriceType.buy,
        dstPriceType: PriceType.buy,
      );

      expect(rows.first.srcPrice, 80.0);
    });

    test('calculates volume and order counts correctly', () {
      final rows = RegionAnalysisComputer.compute(
        orders: [
          _sell(34, 100.0, volumeRemain: 500),
          _sell(34, 110.0, volumeRemain: 300),
          _buy(34, 80.0, volumeRemain: 1000),
        ],
        typeInfo: typeInfo,
        srcPriceType: PriceType.sell,
        dstPriceType: PriceType.sell,
      );

      expect(rows.first.volume, 1800); // 500 + 300 + 1000
      expect(rows.first.sellOrderCount, 2);
      expect(rows.first.buyOrderCount, 1);
    });

    test('calculates sell buyout correctly', () {
      // sell buyout = sum(price × volumeRemain) for all sell orders
      // = 100×500 + 110×300 = 50000 + 33000 = 83000
      final rows = RegionAnalysisComputer.compute(
        orders: [
          _sell(34, 100.0, volumeRemain: 500),
          _sell(34, 110.0, volumeRemain: 300),
          _buy(34, 80.0, volumeRemain: 1000), // excluded from buyout
        ],
        typeInfo: typeInfo,
        srcPriceType: PriceType.sell,
        dstPriceType: PriceType.sell,
      );

      expect(rows.first.sellBuyout, closeTo(83000.0, 0.001));
    });

    test('sorts results by margin descending', () {
      final rows = RegionAnalysisComputer.compute(
        orders: [
          // Tritanium: buy=80, sell=100 → margin=20%
          _buy(34, 80.0),
          _sell(34, 100.0),
          // Pyerite: buy=90, sell=100 → margin=10%
          _buy(35, 90.0),
          _sell(35, 100.0),
        ],
        typeInfo: typeInfo,
        srcPriceType: PriceType.buy,
        dstPriceType: PriceType.sell,
      );

      expect(rows.length, 2);
      expect(rows.first.typeName, 'Tritanium'); // 20% margin
      expect(rows.last.typeName, 'Pyerite');    // 10% margin
    });

    test('filters by stationId when provided', () {
      final rows = RegionAnalysisComputer.compute(
        orders: [
          _sell(34, 100.0, locationId: 60003760), // Jita
          _sell(34, 200.0, locationId: 60008494), // Amarr (excluded)
        ],
        typeInfo: typeInfo,
        srcPriceType: PriceType.sell,
        dstPriceType: PriceType.sell,
        stationId: 60003760,
      );

      expect(rows.length, 1);
      expect(rows.first.srcPrice, 100.0);
    });

    test('handles empty orders', () {
      final rows = RegionAnalysisComputer.compute(
        orders: [],
        typeInfo: typeInfo,
        srcPriceType: PriceType.sell,
        dstPriceType: PriceType.sell,
      );

      expect(rows, isEmpty);
    });

    test('falls back to ID string when type not in typeInfo', () {
      final rows = RegionAnalysisComputer.compute(
        orders: [
          _sell(9999, 100.0),
          _buy(9999, 80.0),
        ],
        typeInfo: {},
        srcPriceType: PriceType.buy,
        dstPriceType: PriceType.sell,
      );

      expect(rows.first.typeName, contains('9999'));
    });

    test('handles zero dstPrice gracefully (margin = 0)', () {
      final rows = RegionAnalysisComputer.compute(
        orders: [
          _buy(34, 100.0),
          // No sell orders → dstPrice = 0
        ],
        typeInfo: typeInfo,
        srcPriceType: PriceType.buy,
        dstPriceType: PriceType.sell,
      );

      expect(rows.first.margin, 0);
    });
  });
}

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

MarketOrder _sell(int typeId, double price, {int volumeRemain = 1000, int locationId = 60003760}) => MarketOrder(
      orderId: typeId * 1000 + locationId % 10000,
      isBuyOrder: false,
      price: price,
      volumeRemain: volumeRemain,
      volumeTotal: 1000,
      locationId: locationId,
      typeId: typeId,
    );

MarketOrder _buy(int typeId, double price, {int volumeRemain = 1000, int locationId = 60003760}) => MarketOrder(
      orderId: typeId * 10000 + locationId % 10000,
      isBuyOrder: true,
      price: price,
      volumeRemain: volumeRemain,
      volumeTotal: 1000,
      locationId: locationId,
      typeId: typeId,
    );
