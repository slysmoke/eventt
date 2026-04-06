import 'package:flutter_test/flutter_test.dart';

import 'package:eve_ntt/core/sde/sde_models.dart';
import 'package:eve_ntt/features/market_browser/data/market_order_repository.dart';
import 'package:eve_ntt/features/market_analysis/domain/inter_region_computer.dart';
import 'package:eve_ntt/features/market_analysis/domain/import_params.dart';

const _srcRegion = MapRegion(regionId: 10000002, regionName: 'The Forge');
const _dstRegion = MapRegion(regionId: 10000043, regionName: 'Domain');

void main() {
  final typeInfo = {
    34: const InvType(typeId: 34, typeName: 'Tritanium', volume: 0.01),
    35: const InvType(typeId: 35, typeName: 'Pyerite', volume: 0.02),
  };

  group('InterRegionAnalysisComputer.compute()', () {
    test('returns rows with correct margin calculation', () {
      // Src: buy=80, sell=100; Dst: buy=110, sell=130
      // srcPrice=sell=100, dstPrice=sell=130 → diff=30, margin=23.08%
      final rows = InterRegionAnalysisComputer.compute(
        srcOrders: [
          _buy(34, 80.0),
          _sell(34, 100.0),
        ],
        dstOrders: [
          _buy(34, 110.0),
          _sell(34, 130.0),
        ],
        srcRegion: _srcRegion,
        dstRegion: _dstRegion,
        typeInfo: typeInfo,
        srcPriceType: PriceType.sell,
        dstPriceType: PriceType.sell,
      );

      expect(rows.length, 1);
      expect(rows.first.typeName, 'Tritanium');
      expect(rows.first.srcSellPrice, 100.0);
      expect(rows.first.dstSellPrice, 130.0);
      expect(rows.first.difference, closeTo(30.0, 0.001));
      expect(rows.first.margin, closeTo(23.077, 0.001));
    });

    test('uses buy prices when srcPriceType=buy, dstPriceType=buy', () {
      final rows = InterRegionAnalysisComputer.compute(
        srcOrders: [
          _buy(34, 80.0),
          _sell(34, 100.0),
        ],
        dstOrders: [
          _buy(34, 120.0),
          _sell(34, 130.0),
        ],
        srcRegion: _srcRegion,
        dstRegion: _dstRegion,
        typeInfo: typeInfo,
        srcPriceType: PriceType.buy,
        dstPriceType: PriceType.buy,
      );

      expect(rows.first.difference, closeTo(40.0, 0.001)); // 120 - 80
      expect(rows.first.margin, closeTo(33.333, 0.001));   // 40/120 × 100
    });

    test('only includes types present in both regions', () {
      final rows = InterRegionAnalysisComputer.compute(
        srcOrders: [
          _sell(34, 100.0), // Tritanium in src
          _sell(35, 150.0), // Pyerite in src
        ],
        dstOrders: [
          _sell(34, 200.0), // Tritanium in dst (only common type)
        ],
        srcRegion: _srcRegion,
        dstRegion: _dstRegion,
        typeInfo: typeInfo,
        srcPriceType: PriceType.sell,
        dstPriceType: PriceType.sell,
      );

      expect(rows.length, 1);
      expect(rows.first.typeId, 34);
    });

    test('calculates order counts correctly', () {
      final rows = InterRegionAnalysisComputer.compute(
        srcOrders: [
          _sell(34, 100.0),
          _sell(34, 110.0),
          _buy(34, 80.0),
        ],
        dstOrders: [
          _sell(34, 200.0),
          _buy(34, 180.0),
        ],
        srcRegion: _srcRegion,
        dstRegion: _dstRegion,
        typeInfo: typeInfo,
        srcPriceType: PriceType.sell,
        dstPriceType: PriceType.sell,
      );

      expect(rows.first.srcOrderCount, 3);
      expect(rows.first.dstOrderCount, 2);
    });

    test('calculates sell buyout correctly', () {
      // Src sell buyout = 100×500 + 110×300 = 83000
      final rows = InterRegionAnalysisComputer.compute(
        srcOrders: [
          _sell(34, 100.0, volumeRemain: 500),
          _sell(34, 110.0, volumeRemain: 300),
        ],
        dstOrders: [
          _sell(34, 200.0, volumeRemain: 1000),
        ],
        srcRegion: _srcRegion,
        dstRegion: _dstRegion,
        typeInfo: typeInfo,
        srcPriceType: PriceType.sell,
        dstPriceType: PriceType.sell,
      );

      expect(rows.first.srcSellBuyout, closeTo(83000.0, 0.001));
      expect(rows.first.dstSellBuyout, closeTo(200000.0, 0.001));
    });

    test('calculates total volume across both regions', () {
      final rows = InterRegionAnalysisComputer.compute(
        srcOrders: [
          _sell(34, 100.0, volumeRemain: 500),
          _buy(34, 80.0, volumeRemain: 300),
        ],
        dstOrders: [
          _sell(34, 200.0, volumeRemain: 1000),
        ],
        srcRegion: _srcRegion,
        dstRegion: _dstRegion,
        typeInfo: typeInfo,
        srcPriceType: PriceType.sell,
        dstPriceType: PriceType.sell,
      );

      expect(rows.first.volume, 1800); // 500 + 300 + 1000
    });

    test('sorts results by margin descending', () {
      final rows = InterRegionAnalysisComputer.compute(
        srcOrders: [
          // Tritanium: src=100, dst=200 → margin=50%
          _sell(34, 100.0),
          // Pyerite: src=150, dst=200 → margin=25%
          _sell(35, 150.0),
        ],
        dstOrders: [
          _sell(34, 200.0),
          _sell(35, 200.0),
        ],
        srcRegion: _srcRegion,
        dstRegion: _dstRegion,
        typeInfo: typeInfo,
        srcPriceType: PriceType.sell,
        dstPriceType: PriceType.sell,
      );

      expect(rows.length, 2);
      expect(rows.first.typeName, 'Tritanium'); // 50% margin
      expect(rows.last.typeName, 'Pyerite');    // 25% margin
    });

    test('includes region names in result', () {
      final rows = InterRegionAnalysisComputer.compute(
        srcOrders: [_sell(34, 100.0)],
        dstOrders: [_sell(34, 200.0)],
        srcRegion: _srcRegion,
        dstRegion: _dstRegion,
        typeInfo: typeInfo,
        srcPriceType: PriceType.sell,
        dstPriceType: PriceType.sell,
      );

      expect(rows.first.srcRegionName, 'The Forge');
      expect(rows.first.dstRegionName, 'Domain');
    });

    test('handles empty orders in one region', () {
      final rows = InterRegionAnalysisComputer.compute(
        srcOrders: [_sell(34, 100.0)],
        dstOrders: [],
        srcRegion: _srcRegion,
        dstRegion: _dstRegion,
        typeInfo: typeInfo,
        srcPriceType: PriceType.sell,
        dstPriceType: PriceType.sell,
      );

      expect(rows, isEmpty); // No common types
    });

    test('handles zero dstPrice gracefully (margin = 0)', () {
      final rows = InterRegionAnalysisComputer.compute(
        srcOrders: [_sell(34, 100.0)],
        dstOrders: [_buy(34, 0.0)], // Only buy order at 0
        srcRegion: _srcRegion,
        dstRegion: _dstRegion,
        typeInfo: typeInfo,
        srcPriceType: PriceType.sell,
        dstPriceType: PriceType.sell,
      );

      // dstSellPrice = 0 (no sell orders), margin = 0
      expect(rows.first.margin, 0);
    });

    test('falls back to ID string when type not in typeInfo', () {
      final rows = InterRegionAnalysisComputer.compute(
        srcOrders: [_sell(9999, 100.0)],
        dstOrders: [_sell(9999, 200.0)],
        srcRegion: _srcRegion,
        dstRegion: _dstRegion,
        typeInfo: {},
        srcPriceType: PriceType.sell,
        dstPriceType: PriceType.sell,
      );

      expect(rows.first.typeName, contains('9999'));
    });
  });
}

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

MarketOrder _sell(int typeId, double price, {int volumeRemain = 1000}) => MarketOrder(
      orderId: typeId * 1000 + typeId,
      isBuyOrder: false,
      price: price,
      volumeRemain: volumeRemain,
      volumeTotal: 1000,
      locationId: 60003760,
      typeId: typeId,
    );

MarketOrder _buy(int typeId, double price, {int volumeRemain = 1000}) => MarketOrder(
      orderId: typeId * 10000 + typeId,
      isBuyOrder: true,
      price: price,
      volumeRemain: volumeRemain,
      volumeTotal: 1000,
      locationId: 60003760,
      typeId: typeId,
    );
