import 'package:flutter_test/flutter_test.dart';

import 'package:eventt/core/sde/sde_models.dart';
import 'package:eventt/features/market_browser/data/market_order_repository.dart';
import 'package:eventt/features/market_analysis/domain/ore_reprocessing_computer.dart';
import 'package:eventt/features/market_analysis/domain/ore_reprocessing_row.dart';
import 'package:eventt/features/market_analysis/domain/import_params.dart';

void main() {
  // Veldspar reprocessing: 1 portion (333 units) → 408 Tritanium + 170 Pyerite
  const veldsparInfo = OreReprocessingInfo(
    oreTypeId: 1230,
    portionSize: 333,
    materials: [
      ReprocessedMaterial(typeId: 34, quantity: 408), // Tritanium
      ReprocessedMaterial(typeId: 35, quantity: 170), // Pyerite
    ],
  );

  final oreInfo = {1230: veldsparInfo};

  final typeInfo = {
    1230: const InvType(typeId: 1230, typeName: 'Veldspar', volume: 0.1),
    34: const InvType(typeId: 34, typeName: 'Tritanium', volume: 0.01),
    35: const InvType(typeId: 35, typeName: 'Pyerite', volume: 0.01),
  };

  group('OreReprocessingComputer.compute()', () {
    test('returns profitable reprocessing opportunity', () {
      // Ore: 1 ISK/unit, Minerals: Tritanium=10, Pyerite=10
      // Portions: floor(1000/333) = 3, Volume: 999
      // Cost: 999 × 1 = 999
      // Minerals at 100% yield: 3×(408×10 + 170×10) = 17340
      final rows = OreReprocessingComputer.compute(
        srcOrders: [_sell(1230, 1.0, volumeRemain: 1000)],
        dstOrders: [
          _buy(34, 10.0),  // Tritanium buy
          _buy(35, 10.0),  // Pyerite buy
        ],
        oreInfo: oreInfo,
        typeInfo: typeInfo,
        params: const OreReprocessingParams(
          stationEfficiency: 1.0, // 100% yield for simplicity
          dstPriceType: PriceType.buy,
        ),
      );

      expect(rows.length, 1);
      expect(rows.first.oreName, 'Veldspar');
      expect(rows.first.totalCost, closeTo(999.0, 1.0));
      // 3×(408×10 + 170×10) = 17340 at 100% yield
      expect(rows.first.totalProfit, closeTo(17340.0, 1.0));
    });

    test('excludes unprofitable ores', () {
      // Ore costs more than minerals are worth
      final rows = OreReprocessingComputer.compute(
        srcOrders: [_sell(1230, 1000.0, volumeRemain: 1000)], // expensive ore
        dstOrders: [
          _buy(34, 1.0),
          _buy(35, 1.0),
        ],
        oreInfo: oreInfo,
        typeInfo: typeInfo,
        params: const OreReprocessingParams(
          stationEfficiency: 1.0,
          dstPriceType: PriceType.buy,
        ),
      );

      expect(rows, isEmpty);
    });

    test('applies station efficiency correctly', () {
      // 50% efficiency → half the minerals
      final rows50 = OreReprocessingComputer.compute(
        srcOrders: [_sell(1230, 1.0, volumeRemain: 1000)],
        dstOrders: [
          _buy(34, 10.0),
          _buy(35, 10.0),
        ],
        oreInfo: oreInfo,
        typeInfo: typeInfo,
        params: const OreReprocessingParams(
          stationEfficiency: 0.5,
          dstPriceType: PriceType.buy,
        ),
      );

      // 100% efficiency
      final rows100 = OreReprocessingComputer.compute(
        srcOrders: [_sell(1230, 1.0, volumeRemain: 1000)],
        dstOrders: [
          _buy(34, 10.0),
          _buy(35, 10.0),
        ],
        oreInfo: oreInfo,
        typeInfo: typeInfo,
        params: const OreReprocessingParams(
          stationEfficiency: 1.0,
          dstPriceType: PriceType.buy,
        ),
      );

      expect(rows50.length, 1);
      expect(rows100.length, 1);
      expect(rows50.first.totalProfit, lessThan(rows100.first.totalProfit));
    });

    test('uses sell orders when dstPriceType=sell', () {
      final rows = OreReprocessingComputer.compute(
        srcOrders: [_sell(1230, 10.0, volumeRemain: 1000)],
        dstOrders: [
          _sell(34, 100.0), // mineral sell orders
          _sell(35, 100.0),
        ],
        oreInfo: oreInfo,
        typeInfo: typeInfo,
        params: const OreReprocessingParams(
          stationEfficiency: 1.0,
          dstPriceType: PriceType.sell,
        ),
      );

      expect(rows.length, 1);
    });

    test('handles empty source orders', () {
      final rows = OreReprocessingComputer.compute(
        srcOrders: [],
        dstOrders: [
          _buy(34, 10.0),
          _buy(35, 10.0),
        ],
        oreInfo: oreInfo,
        typeInfo: typeInfo,
        params: const OreReprocessingParams(
          stationEfficiency: 1.0,
          dstPriceType: PriceType.buy,
        ),
      );

      expect(rows, isEmpty);
    });

    test('handles empty destination orders', () {
      final rows = OreReprocessingComputer.compute(
        srcOrders: [_sell(1230, 10.0, volumeRemain: 1000)],
        dstOrders: [],
        oreInfo: oreInfo,
        typeInfo: typeInfo,
        params: const OreReprocessingParams(
          stationEfficiency: 1.0,
          dstPriceType: PriceType.buy,
        ),
      );

      expect(rows, isEmpty); // No minerals to sell
    });

    test('sorts results by margin descending', () {
      // Add another ore type
      const scorditeInfo = OreReprocessingInfo(
        oreTypeId: 1231,
        portionSize: 500,
        materials: [
          ReprocessedMaterial(typeId: 34, quantity: 600),
        ],
      );

      final extendedOreInfo = {1230: veldsparInfo, 1231: scorditeInfo};
      final extendedTypeInfo = {
        ...typeInfo,
        1231: const InvType(typeId: 1231, typeName: 'Scordite', volume: 0.15),
      };

      final rows = OreReprocessingComputer.compute(
        srcOrders: [
          _sell(1230, 10.0, volumeRemain: 1000), // lower margin
          _sell(1231, 5.0, volumeRemain: 1000),  // higher margin
        ],
        dstOrders: [
          _buy(34, 50.0),
        ],
        oreInfo: extendedOreInfo,
        typeInfo: extendedTypeInfo,
        params: const OreReprocessingParams(
          stationEfficiency: 1.0,
          dstPriceType: PriceType.buy,
        ),
      );

      expect(rows.length, 2);
      expect(rows.first.margin, greaterThanOrEqualTo(rows.last.margin));
    });

    test('falls back to ID string when type not in typeInfo', () {
      final rows = OreReprocessingComputer.compute(
        srcOrders: [_sell(9999, 10.0, volumeRemain: 1000)],
        dstOrders: [_buy(34, 100.0)],
        oreInfo: {
          9999: const OreReprocessingInfo(
            oreTypeId: 9999,
            portionSize: 100,
            materials: [ReprocessedMaterial(typeId: 34, quantity: 50)],
          ),
        },
        typeInfo: {},
        params: const OreReprocessingParams(
          stationEfficiency: 1.0,
          dstPriceType: PriceType.buy,
        ),
      );

      expect(rows.first.oreName, contains('9999'));
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
