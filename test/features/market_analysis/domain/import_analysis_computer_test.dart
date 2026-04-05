import 'package:flutter_test/flutter_test.dart';

import 'package:eventt/core/sde/sde_models.dart';
import 'package:eventt/features/market_browser/data/market_order_repository.dart';
import 'package:eventt/features/market_analysis/domain/import_analysis_computer.dart';
import 'package:eventt/features/market_analysis/domain/import_params.dart';

const _srcStation = 60003760; // Jita 4-4
const _dstStation = 60008494; // Amarr VIII

// Sell/buy at source=Jita, sell/buy at destination=Amarr (src=Sell, dst=Sell)
const _sellSell = ImportParams(
  srcPriceType: PriceType.sell,
  dstPriceType: PriceType.sell,
);

// Default (src=Sell, dst=Buy) — conservative/instant
const _defaultParams = ImportParams();

void main() {
  final typeInfo = {
    34: const InvType(typeId: 34, typeName: 'Tritanium', volume: 0.01),
    35: const InvType(typeId: 35, typeName: 'Pyerite', volume: 0.01),
  };

  group('ImportAnalysisComputer.compute() — sell/sell pricing', () {
    test('returns row with correct margin', () {
      final rows = ImportAnalysisComputer.compute(
        allSrcOrders: [_sell(34, 100.0, _srcStation)],
        allDstOrders: [_sell(34, 200.0, _dstStation)],
        srcStationId: _srcStation,
        dstStationId: _dstStation,
        typeInfo: typeInfo,
        params: _sellSell,
      );

      expect(rows.length, 1);
      expect(rows.first.typeName, 'Tritanium');
      expect(rows.first.sourcePrice, 100.0);
      expect(rows.first.destPrice, 200.0);
      expect(rows.first.priceDiff, closeTo(100.0, 0.001));
      expect(rows.first.margin, closeTo(50.0, 0.001)); // 100/200 × 100
    });

    test('uses minimum sell price at each station', () {
      final rows = ImportAnalysisComputer.compute(
        allSrcOrders: [
          _sell(34, 150.0, _srcStation),
          _sell(34, 100.0, _srcStation), // cheapest
          _sell(34, 200.0, _srcStation),
        ],
        allDstOrders: [
          _sell(34, 300.0, _dstStation),
          _sell(34, 250.0, _dstStation), // cheapest at dst
        ],
        srcStationId: _srcStation,
        dstStationId: _dstStation,
        typeInfo: typeInfo,
        params: _sellSell,
      );

      expect(rows.first.sourcePrice, 100.0);
      expect(rows.first.destPrice, 250.0);
    });

    test('excludes orders from wrong stations', () {
      final rows = ImportAnalysisComputer.compute(
        allSrcOrders: [_sell(34, 100.0, 9999999)], // wrong station
        allDstOrders: [_sell(34, 200.0, _dstStation)],
        srcStationId: _srcStation,
        dstStationId: _dstStation,
        typeInfo: typeInfo,
        params: _sellSell,
      );

      expect(rows, isEmpty);
    });

    test('excludes buy orders when srcPriceType=sell', () {
      final rows = ImportAnalysisComputer.compute(
        allSrcOrders: [
          MarketOrder(
            orderId: 1, isBuyOrder: true, price: 100.0,
            volumeRemain: 1000, volumeTotal: 1000,
            locationId: _srcStation, typeId: 34,
          ),
        ],
        allDstOrders: [_sell(34, 200.0, _dstStation)],
        srcStationId: _srcStation,
        dstStationId: _dstStation,
        typeInfo: typeInfo,
        params: _sellSell,
      );
      expect(rows, isEmpty);
    });

    test('excludes types with no destination orders', () {
      final rows = ImportAnalysisComputer.compute(
        allSrcOrders: [_sell(34, 100.0, _srcStation)],
        allDstOrders: [],
        srcStationId: _srcStation,
        dstStationId: _dstStation,
        typeInfo: typeInfo,
        params: _sellSell,
      );
      expect(rows, isEmpty);
    });

    test('excludes unprofitable items', () {
      final rows = ImportAnalysisComputer.compute(
        allSrcOrders: [_sell(34, 200.0, _srcStation)],
        allDstOrders: [_sell(34, 150.0, _dstStation)],
        srcStationId: _srcStation,
        dstStationId: _dstStation,
        typeInfo: typeInfo,
        params: _sellSell,
      );
      expect(rows, isEmpty);
    });

    test('sorts results by margin descending', () {
      final rows = ImportAnalysisComputer.compute(
        allSrcOrders: [
          _sell(34, 100.0, _srcStation), // 50% margin
          _sell(35, 180.0, _srcStation), // 10% margin
        ],
        allDstOrders: [
          _sell(34, 200.0, _dstStation),
          _sell(35, 200.0, _dstStation),
        ],
        srcStationId: _srcStation,
        dstStationId: _dstStation,
        typeInfo: typeInfo,
        params: _sellSell,
      );
      expect(rows.length, 2);
      expect(rows.first.margin, greaterThan(rows.last.margin));
    });

    test('empty orders returns empty result', () {
      expect(
        ImportAnalysisComputer.compute(
          allSrcOrders: [],
          allDstOrders: [],
          srcStationId: _srcStation,
          dstStationId: _dstStation,
          typeInfo: typeInfo,
          params: _sellSell,
        ),
        isEmpty,
      );
    });
  });

  group('ImportAnalysisComputer.compute() — price types', () {
    test('srcPriceType=buy uses max buy order price', () {
      // Buy order at src at 80 — that's what we'd pay placing a buy order
      final rows = ImportAnalysisComputer.compute(
        allSrcOrders: [
          _buy(34, 80.0, _srcStation),  // best (only) buy order
          _buy(34, 70.0, _srcStation),  // lower — should be ignored
        ],
        allDstOrders: [_sell(34, 200.0, _dstStation)],
        srcStationId: _srcStation,
        dstStationId: _dstStation,
        typeInfo: typeInfo,
        params: const ImportParams(
          srcPriceType: PriceType.buy,
          dstPriceType: PriceType.sell,
        ),
      );

      expect(rows.length, 1);
      expect(rows.first.sourcePrice, 80.0); // max buy order
    });

    test('dstPriceType=buy uses max buy order price at destination', () {
      // Sell orders at dst exist, but we want buy order price
      final rows = ImportAnalysisComputer.compute(
        allSrcOrders: [_sell(34, 100.0, _srcStation)],
        allDstOrders: [
          _buy(34, 180.0, _dstStation), // best buy order at dst
          _sell(34, 200.0, _dstStation), // sell order — should be ignored
        ],
        srcStationId: _srcStation,
        dstStationId: _dstStation,
        typeInfo: typeInfo,
        params: _defaultParams, // dst=buy by default
      );

      expect(rows.length, 1);
      expect(rows.first.destPrice, 180.0); // max buy order
    });

    test('dstPriceType=sell uses min sell order price', () {
      final rows = ImportAnalysisComputer.compute(
        allSrcOrders: [_sell(34, 100.0, _srcStation)],
        allDstOrders: [
          _sell(34, 200.0, _dstStation), // sell order — used
          _buy(34, 250.0, _dstStation),  // buy order — should be ignored
        ],
        srcStationId: _srcStation,
        dstStationId: _dstStation,
        typeInfo: typeInfo,
        params: _sellSell,
      );

      expect(rows.first.destPrice, 200.0);
    });
  });

  group('ImportAnalysisComputer.compute() — logistics and filters', () {
    test('applies pricePerM3 logistics cost', () {
      // src=100, vol=0.01m³, pricePerM3=1000 → logistics=10
      // importPrice = 100+10 = 110, dst=200 → margin=45%
      final rows = ImportAnalysisComputer.compute(
        allSrcOrders: [_sell(34, 100.0, _srcStation)],
        allDstOrders: [_sell(34, 200.0, _dstStation)],
        srcStationId: _srcStation,
        dstStationId: _dstStation,
        typeInfo: typeInfo,
        params: const ImportParams(
          srcPriceType: PriceType.sell,
          dstPriceType: PriceType.sell,
          pricePerM3: 1000,
        ),
      );

      expect(rows.first.importPrice, closeTo(110.0, 0.001));
      expect(rows.first.priceDiff, closeTo(90.0, 0.001));
    });

    test('applies collateral on source price (collateralPriceType=buy)', () {
      // src=100, collateral=10%, based on src (buy price) → +10 → importPrice=110
      final rows = ImportAnalysisComputer.compute(
        allSrcOrders: [_sell(34, 100.0, _srcStation)],
        allDstOrders: [_sell(34, 200.0, _dstStation)],
        srcStationId: _srcStation,
        dstStationId: _dstStation,
        typeInfo: typeInfo,
        params: const ImportParams(
          srcPriceType: PriceType.sell,
          dstPriceType: PriceType.sell,
          collateralPct: 10,
          collateralPriceType: PriceType.buy,
        ),
      );

      expect(rows.first.importPrice, closeTo(110.0, 0.001));
    });

    test('applies collateral on destination price (collateralPriceType=sell)', () {
      // src=100, dst=200, collateral=10% of dst → +20 → importPrice=120
      final rows = ImportAnalysisComputer.compute(
        allSrcOrders: [_sell(34, 100.0, _srcStation)],
        allDstOrders: [_sell(34, 200.0, _dstStation)],
        srcStationId: _srcStation,
        dstStationId: _dstStation,
        typeInfo: typeInfo,
        params: const ImportParams(
          srcPriceType: PriceType.sell,
          dstPriceType: PriceType.sell,
          collateralPct: 10,
          collateralPriceType: PriceType.sell,
        ),
      );

      expect(rows.first.importPrice, closeTo(120.0, 0.001));
    });

    test('applies source price modifier', () {
      // src=100, srcPriceMod=10% → importPrice = 100×1.1 = 110, dst=200 → margin=45%
      final rows = ImportAnalysisComputer.compute(
        allSrcOrders: [_sell(34, 100.0, _srcStation)],
        allDstOrders: [_sell(34, 200.0, _dstStation)],
        srcStationId: _srcStation,
        dstStationId: _dstStation,
        typeInfo: typeInfo,
        params: const ImportParams(
          srcPriceType: PriceType.sell,
          dstPriceType: PriceType.sell,
          srcPriceMod: 10,
        ),
      );

      expect(rows.first.importPrice, closeTo(110.0, 0.001));
    });

    test('respects minMarginPct filter', () {
      // Tritanium 50%, Pyerite 10%
      final rows = ImportAnalysisComputer.compute(
        allSrcOrders: [
          _sell(34, 100.0, _srcStation),
          _sell(35, 180.0, _srcStation),
        ],
        allDstOrders: [
          _sell(34, 200.0, _dstStation),
          _sell(35, 200.0, _dstStation),
        ],
        srcStationId: _srcStation,
        dstStationId: _dstStation,
        typeInfo: typeInfo,
        params: const ImportParams(
          srcPriceType: PriceType.sell,
          dstPriceType: PriceType.sell,
          minMarginPct: 30,
        ),
      );

      expect(rows.length, 1);
      expect(rows.first.typeName, 'Tritanium');
    });

    test('respects maxMarginPct filter', () {
      // Tritanium 50% — excluded by maxMargin=20%; Pyerite 10% — included
      final rows = ImportAnalysisComputer.compute(
        allSrcOrders: [
          _sell(34, 100.0, _srcStation),
          _sell(35, 180.0, _srcStation),
        ],
        allDstOrders: [
          _sell(34, 200.0, _dstStation),
          _sell(35, 200.0, _dstStation),
        ],
        srcStationId: _srcStation,
        dstStationId: _dstStation,
        typeInfo: typeInfo,
        params: const ImportParams(
          srcPriceType: PriceType.sell,
          dstPriceType: PriceType.sell,
          maxMarginPct: 20,
        ),
      );

      expect(rows.length, 1);
      expect(rows.first.typeName, 'Pyerite');
    });

    test('typeFilter restricts results to given type IDs', () {
      final rows = ImportAnalysisComputer.compute(
        allSrcOrders: [
          _sell(34, 100.0, _srcStation),
          _sell(35, 100.0, _srcStation),
        ],
        allDstOrders: [
          _sell(34, 200.0, _dstStation),
          _sell(35, 200.0, _dstStation),
        ],
        srcStationId: _srcStation,
        dstStationId: _dstStation,
        typeInfo: typeInfo,
        params: _sellSell,
        typeFilter: {34}, // only Tritanium
      );

      expect(rows.length, 1);
      expect(rows.first.typeId, 34);
    });

    test('falls back to ID string when type not in typeInfo', () {
      final rows = ImportAnalysisComputer.compute(
        allSrcOrders: [_sell(9999, 100.0, _srcStation)],
        allDstOrders: [_sell(9999, 200.0, _dstStation)],
        srcStationId: _srcStation,
        dstStationId: _dstStation,
        typeInfo: {},
        params: _sellSell,
      );
      expect(rows.first.typeName, contains('9999'));
    });
  });

  group('ImportAnalysisComputer.compute() — volume statistics', () {
    test('counts source orders correctly', () {
      final rows = ImportAnalysisComputer.compute(
        allSrcOrders: [
          _sell(34, 100.0, _srcStation),
          _sell(34, 105.0, _srcStation),
          _sell(34, 110.0, _srcStation),
        ],
        allDstOrders: [_sell(34, 200.0, _dstStation)],
        srcStationId: _srcStation,
        dstStationId: _dstStation,
        typeInfo: typeInfo,
        params: _sellSell,
      );

      expect(rows.length, 1);
      expect(rows.first.sourceOrderCount, 3);
    });

    test('counts destination orders correctly', () {
      final rows = ImportAnalysisComputer.compute(
        allSrcOrders: [_sell(34, 100.0, _srcStation)],
        allDstOrders: [
          _sell(34, 200.0, _dstStation),
          _sell(34, 210.0, _dstStation),
        ],
        srcStationId: _srcStation,
        dstStationId: _dstStation,
        typeInfo: typeInfo,
        params: _sellSell,
      );

      expect(rows.first.destOrderCount, 2);
    });

    test('calculates destination remaining volume', () {
      final rows = ImportAnalysisComputer.compute(
        allSrcOrders: [_sell(34, 100.0, _srcStation)],
        allDstOrders: [
          _sell(34, 200.0, _dstStation, volumeRemain: 500),
          _sell(34, 210.0, _dstStation, volumeRemain: 300),
        ],
        srcStationId: _srcStation,
        dstStationId: _dstStation,
        typeInfo: typeInfo,
        params: _sellSell,
      );

      expect(rows.first.destRemainingVolume, 800); // 500 + 300
    });

    test('calculates projected profit from volume and price diff', () {
      // src=100, dst=200, diff=100, volume=800 → projectedProfit=80000
      final rows = ImportAnalysisComputer.compute(
        allSrcOrders: [_sell(34, 100.0, _srcStation)],
        allDstOrders: [
          _sell(34, 200.0, _dstStation, volumeRemain: 500),
          _sell(34, 210.0, _dstStation, volumeRemain: 300),
        ],
        srcStationId: _srcStation,
        dstStationId: _dstStation,
        typeInfo: typeInfo,
        params: _sellSell,
      );

      expect(rows.first.priceDiff, closeTo(100.0, 0.001));
      expect(rows.first.destRemainingVolume, 800);
      expect(rows.first.projectedProfit, closeTo(80000.0, 0.001));
    });

    test('excludes buy orders when counting sell order statistics', () {
      final rows = ImportAnalysisComputer.compute(
        allSrcOrders: [
          _sell(34, 100.0, _srcStation),
          _buy(34, 90.0, _srcStation), // buy order — should be excluded
        ],
        allDstOrders: [_sell(34, 200.0, _dstStation)],
        srcStationId: _srcStation,
        dstStationId: _dstStation,
        typeInfo: typeInfo,
        params: _sellSell,
      );

      expect(rows.first.sourceOrderCount, 1); // only sell counted
    });
  });
}

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

MarketOrder _sell(int typeId, double price, int locationId, {int volumeRemain = 1000}) => MarketOrder(
      orderId: typeId * 1000 + locationId % 10000,
      isBuyOrder: false,
      price: price,
      volumeRemain: volumeRemain,
      volumeTotal: 1000,
      locationId: locationId,
      typeId: typeId,
    );

MarketOrder _buy(int typeId, double price, int locationId, {int volumeRemain = 1000}) => MarketOrder(
      orderId: typeId * 10000 + locationId % 10000,
      isBuyOrder: true,
      price: price,
      volumeRemain: volumeRemain,
      volumeTotal: 1000,
      locationId: locationId,
      typeId: typeId,
    );
