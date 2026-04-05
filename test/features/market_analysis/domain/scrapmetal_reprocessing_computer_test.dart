import 'package:flutter_test/flutter_test.dart';

import 'package:eventt/core/sde/sde_models.dart';
import 'package:eventt/features/market_browser/data/market_order_repository.dart';
import 'package:eventt/features/market_analysis/domain/scrapmetal_reprocessing_computer.dart';
import 'package:eventt/features/market_analysis/domain/import_params.dart';

// 150mm AutoCannon I → 20 Tritanium + 10 Pyerite per 1 unit
const _cannonInfo = ScrapmetalInfo(
  typeId: 12760,
  portionSize: 1,
  materials: [
    ScrapmetalMaterial(typeId: 34, quantity: 20), // Tritanium
    ScrapmetalMaterial(typeId: 35, quantity: 10), // Pyerite
  ],
);

final _itemInfo = {12760: _cannonInfo};

final _typeInfo = {
  12760: const InvType(typeId: 12760, typeName: '150mm AutoCannon I', volume: 5.0),
  34: const InvType(typeId: 34, typeName: 'Tritanium', volume: 0.01),
  35: const InvType(typeId: 35, typeName: 'Pyerite', volume: 0.01),
};

void main() {
  group('ScrapmetalReprocessingComputer.compute()', () {
    test('returns profitable reprocessing opportunity', () {
      // Buy 1 cannon at 10 ISK, reprocess → sell minerals at 5 ISK each
      // 100% efficiency: 1×20 Tritanium ×5 + 1×10 Pyerite ×5 = 150 ISK
      // Cost: 10 ISK → profit 140 ISK
      final rows = ScrapmetalReprocessingComputer.compute(
        srcOrders: [_sell(12760, 10.0, volumeRemain: 1)],
        dstOrders: [
          _buy(34, 5.0),
          _buy(35, 5.0),
        ],
        scrapmetalInfo: _itemInfo,
        typeInfo: _typeInfo,
        params: const ScrapmetalReprocessingParams(
          stationEfficiency: 1.0,
          dstPriceType: PriceType.buy,
        ),
      );

      expect(rows.length, 1);
      expect(rows.first.typeName, '150mm AutoCannon I');
      expect(rows.first.totalCost, closeTo(10.0, 0.1));
      expect(rows.first.totalProfit, closeTo(150.0, 1.0));
      expect(rows.first.margin, greaterThan(0));
    });

    test('excludes unprofitable items', () {
      // Buy cannon at 1000 ISK, minerals worth 150 ISK (1 unit) → loss
      final rows = ScrapmetalReprocessingComputer.compute(
        srcOrders: [_sell(12760, 1000.0, volumeRemain: 1)],
        dstOrders: [
          _buy(34, 5.0),
          _buy(35, 5.0),
        ],
        scrapmetalInfo: _itemInfo,
        typeInfo: _typeInfo,
        params: const ScrapmetalReprocessingParams(
          stationEfficiency: 1.0,
          dstPriceType: PriceType.buy,
        ),
      );

      expect(rows, isEmpty);
    });

    test('applies station efficiency correctly', () {
      final rows50 = ScrapmetalReprocessingComputer.compute(
        srcOrders: [_sell(12760, 10.0)],
        dstOrders: [_buy(34, 5.0), _buy(35, 5.0)],
        scrapmetalInfo: _itemInfo,
        typeInfo: _typeInfo,
        params: const ScrapmetalReprocessingParams(
          stationEfficiency: 0.5,
          dstPriceType: PriceType.buy,
        ),
      );

      final rows100 = ScrapmetalReprocessingComputer.compute(
        srcOrders: [_sell(12760, 10.0)],
        dstOrders: [_buy(34, 5.0), _buy(35, 5.0)],
        scrapmetalInfo: _itemInfo,
        typeInfo: _typeInfo,
        params: const ScrapmetalReprocessingParams(
          stationEfficiency: 1.0,
          dstPriceType: PriceType.buy,
        ),
      );

      // Both profitable; 100% yields more
      expect(rows100.first.totalProfit, greaterThan(rows50.first.totalProfit));
    });

    test('applies scrapmetal skill level correctly', () {
      final rowsSkill0 = ScrapmetalReprocessingComputer.compute(
        srcOrders: [_sell(12760, 10.0)],
        dstOrders: [_buy(34, 5.0), _buy(35, 5.0)],
        scrapmetalInfo: _itemInfo,
        typeInfo: _typeInfo,
        params: const ScrapmetalReprocessingParams(
          stationEfficiency: 0.5,
          scrapmetalSkillLevel: 0,
          dstPriceType: PriceType.buy,
        ),
      );

      final rowsSkill5 = ScrapmetalReprocessingComputer.compute(
        srcOrders: [_sell(12760, 10.0)],
        dstOrders: [_buy(34, 5.0), _buy(35, 5.0)],
        scrapmetalInfo: _itemInfo,
        typeInfo: _typeInfo,
        params: const ScrapmetalReprocessingParams(
          stationEfficiency: 0.5,
          scrapmetalSkillLevel: 5,
          dstPriceType: PriceType.buy,
        ),
      );

      expect(rowsSkill5.first.totalProfit, greaterThan(rowsSkill0.first.totalProfit));
    });

    test('uses sell orders when dstPriceType=sell', () {
      // 1000 units at portionSize=1 → 1000 portions
      // 1000×20 Tritanium ×100 + 1000×10 Pyerite ×100 = 3,000,000
      final rows = ScrapmetalReprocessingComputer.compute(
        srcOrders: [_sell(12760, 10.0)],
        dstOrders: [
          _sell(34, 100.0),
          _sell(35, 100.0),
        ],
        scrapmetalInfo: _itemInfo,
        typeInfo: _typeInfo,
        params: const ScrapmetalReprocessingParams(
          stationEfficiency: 1.0,
          dstPriceType: PriceType.sell,
        ),
      );

      expect(rows.length, 1);
      expect(rows.first.totalProfit, closeTo(3000000.0, 100.0));
    });

    test('handles empty source orders', () {
      final rows = ScrapmetalReprocessingComputer.compute(
        srcOrders: [],
        dstOrders: [_buy(34, 5.0)],
        scrapmetalInfo: _itemInfo,
        typeInfo: _typeInfo,
        params: const ScrapmetalReprocessingParams(stationEfficiency: 1.0),
      );

      expect(rows, isEmpty);
    });

    test('handles empty destination orders', () {
      final rows = ScrapmetalReprocessingComputer.compute(
        srcOrders: [_sell(12760, 10.0)],
        dstOrders: [],
        scrapmetalInfo: _itemInfo,
        typeInfo: _typeInfo,
        params: const ScrapmetalReprocessingParams(stationEfficiency: 1.0),
      );

      expect(rows, isEmpty);
    });

    test('sorts results by margin descending', () {
      const rifleInfo = ScrapmetalInfo(
        typeId: 9999,
        portionSize: 1,
        materials: [ScrapmetalMaterial(typeId: 34, quantity: 5)],
      );
      final extendedInfo = {12760: _cannonInfo, 9999: rifleInfo};
      final extendedTypeInfo = {
        ..._typeInfo,
        9999: const InvType(typeId: 9999, typeName: 'Rifle I', volume: 3.0),
      };

      final rows = ScrapmetalReprocessingComputer.compute(
        srcOrders: [
          _sell(12760, 10.0),  // high margin
          _sell(9999, 8.0),    // lower margin (fewer minerals)
        ],
        dstOrders: [_buy(34, 50.0), _buy(35, 50.0)],
        scrapmetalInfo: extendedInfo,
        typeInfo: extendedTypeInfo,
        params: const ScrapmetalReprocessingParams(
          stationEfficiency: 1.0,
          dstPriceType: PriceType.buy,
        ),
      );

      expect(rows.length, 2);
      expect(rows.first.margin, greaterThanOrEqualTo(rows.last.margin));
    });

    test('falls back to ID string when type not in typeInfo', () {
      final rows = ScrapmetalReprocessingComputer.compute(
        srcOrders: [_sell(12760, 10.0)],
        dstOrders: [_buy(34, 5.0), _buy(35, 5.0)],
        scrapmetalInfo: _itemInfo,
        typeInfo: {}, // no name lookup
        params: const ScrapmetalReprocessingParams(stationEfficiency: 1.0),
      );

      expect(rows.first.typeName, contains('12760'));
    });
  });
}

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

MarketOrder _sell(int typeId, double price, {int volumeRemain = 1000}) =>
    MarketOrder(
      orderId: typeId * 1000 + typeId,
      isBuyOrder: false,
      price: price,
      volumeRemain: volumeRemain,
      volumeTotal: 1000,
      locationId: 60003760,
      typeId: typeId,
    );

MarketOrder _buy(int typeId, double price, {int volumeRemain = 1000}) =>
    MarketOrder(
      orderId: typeId * 10000 + typeId,
      isBuyOrder: true,
      price: price,
      volumeRemain: volumeRemain,
      volumeTotal: 1000,
      locationId: 60003760,
      typeId: typeId,
    );
