import 'package:flutter_test/flutter_test.dart';

import 'package:eventt/core/margin/margin_calculator.dart';

void main() {
  group('MarginCalculator.compute()', () {
    test('returns null when buy or sell price is zero', () {
      expect(
          MarginCalculator.compute(
              buyPrice: 0, sellPrice: 100, params: const MarginParams()),
          isNull);
      expect(
          MarginCalculator.compute(
              buyPrice: 100, sellPrice: 0, params: const MarginParams()),
          isNull);
    });

    test('calculates gross margin with no fees', () {
      // buy=80, sell=100, no fees → margin = (100−80)/100 = 20%
      final r = MarginCalculator.compute(
        buyPrice: 80,
        sellPrice: 100,
        params: const MarginParams(
          brokerFeePct: 0,
          salesTaxPct: 0,
        ),
      )!;
      expect(r.margin, closeTo(20.0, 0.01));
    });

    test('deducts broker fee from profit', () {
      // buy=80, sell=100, brokerFee=2% of sell=2, no tax
      // net profit = 100−80−2 = 18, margin = 18/100 = 18%
      final r = MarginCalculator.compute(
        buyPrice: 80,
        sellPrice: 100,
        params: const MarginParams(brokerFeePct: 2.0, salesTaxPct: 0),
      )!;
      expect(r.profit, closeTo(18.0, 0.01));
      expect(r.margin, closeTo(18.0, 0.01));
    });

    test('deducts sales tax from profit', () {
      // buy=80, sell=100, tax=3.6% of sell=3.6, no broker fee
      // net profit = 100−80−3.6 = 16.4, margin = 16.4/100 = 16.4%
      final r = MarginCalculator.compute(
        buyPrice: 80,
        sellPrice: 100,
        params: const MarginParams(brokerFeePct: 0, salesTaxPct: 3.6),
      )!;
      expect(r.profit, closeTo(16.4, 0.01));
      expect(r.margin, closeTo(16.4, 0.01));
    });

    test('deducts both broker fee and sales tax', () {
      // buy=80, sell=100, fee=2%, tax=3.6%
      // fee = 2.0, tax = 3.6
      // profit = 100−80−2−3.6 = 14.4
      final r = MarginCalculator.compute(
        buyPrice: 80,
        sellPrice: 100,
        params: const MarginParams(brokerFeePct: 2.0, salesTaxPct: 3.6),
      )!;
      expect(r.profit, closeTo(14.4, 0.01));
    });

    test('also deducts broker fee on buy order when includeBuyBrokerFee=true', () {
      // buy=80, sell=100, fee=2% both sides, tax=0
      // buy-side fee = 1.6, sell-side fee = 2.0
      // profit = 100−80−1.6−2.0 = 16.4
      final r = MarginCalculator.compute(
        buyPrice: 80,
        sellPrice: 100,
        params: const MarginParams(
          brokerFeePct: 2.0,
          salesTaxPct: 0,
          includeBuyBrokerFee: true,
        ),
      )!;
      expect(r.profit, closeTo(16.4, 0.01));
    });

    test('returns negative margin when unprofitable', () {
      // buy=100, sell=100 with 5% fee → loss
      final r = MarginCalculator.compute(
        buyPrice: 100,
        sellPrice: 100,
        params: const MarginParams(brokerFeePct: 5.0, salesTaxPct: 0),
      )!;
      expect(r.margin, lessThan(0));
      expect(r.isProfit, isFalse);
    });

    test('isProfit is true when profit > 0', () {
      final r = MarginCalculator.compute(
        buyPrice: 80,
        sellPrice: 100,
        params: const MarginParams(),
      )!;
      expect(r.isProfit, isTrue);
    });

    test('breakEvenSellPrice covers all costs', () {
      // At breakEvenSellPrice, profit == 0
      // buy=80, fee=2% on sell, tax=3.6% on sell
      // breakEven: sell = 80 / (1 - 0.02 - 0.036) = 80 / 0.944 ≈ 84.75
      final r = MarginCalculator.compute(
        buyPrice: 80,
        sellPrice: 100,
        params: const MarginParams(brokerFeePct: 2.0, salesTaxPct: 3.6),
      )!;
      final beSell = r.breakEvenSellPrice;
      // Verify: at breakEvenSellPrice, profit = 0
      final check = MarginCalculator.compute(
        buyPrice: 80,
        sellPrice: beSell,
        params: const MarginParams(brokerFeePct: 2.0, salesTaxPct: 3.6),
      )!;
      expect(check.profit.abs(), lessThan(0.01));
    });

    test('orderOneSellPrice is sell − 0.01 ISK', () {
      // When market bestSellPrice=100, your order should be at 99.99
      final r = MarginCalculator.compute(
        buyPrice: 80,
        sellPrice: 100,
        params: const MarginParams(),
      )!;
      expect(r.orderOneSellPrice, closeTo(99.99, 0.001));
    });
  });
}
