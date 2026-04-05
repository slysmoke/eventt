/// Parameters for margin calculation: broker fees and sales tax.
class MarginParams {
  /// Broker fee as percentage of sell order value (0–100).
  /// Typical range: 0.1–5%.
  final double brokerFeePct;

  /// Sales tax as percentage of sell order value (0–100).
  /// Reduces with Accounting skill: base 8%, -10% per level → min 4%.
  final double salesTaxPct;

  /// Whether to also charge broker fee on the buy order.
  /// False = you're buying from NPC sell orders (no fee).
  /// True  = you placed a buy order yourself.
  final bool includeBuyBrokerFee;

  const MarginParams({
    this.brokerFeePct = 2.0,
    this.salesTaxPct = 3.6,
    this.includeBuyBrokerFee = false,
  });
}

/// Result of a margin calculation.
class MarginResult {
  /// The buy price (cost per unit).
  final double buyPrice;

  /// The sell price used for calculation.
  final double sellPrice;

  /// Broker fee charged on the sell order.
  final double brokerFee;

  /// Sales tax charged on the sell order.
  final double salesTax;

  /// Broker fee charged on the buy order (0 if [MarginParams.includeBuyBrokerFee] is false).
  final double buyBrokerFee;

  /// Net profit per unit after all fees (can be negative).
  final double profit;

  /// Net margin as percentage of sell price (can be negative).
  final double margin;

  /// Minimum sell price that exactly covers all costs (profit = 0).
  final double breakEvenSellPrice;

  /// Suggested sell order price to be #1 on the market (sellPrice − 0.01).
  final double orderOneSellPrice;

  const MarginResult({
    required this.buyPrice,
    required this.sellPrice,
    required this.brokerFee,
    required this.salesTax,
    required this.buyBrokerFee,
    required this.profit,
    required this.margin,
    required this.breakEvenSellPrice,
    required this.orderOneSellPrice,
  });

  bool get isProfit => profit > 0;
}

/// Pure margin calculation for EVE Online trading.
class MarginCalculator {
  MarginCalculator._();

  /// Computes margin given [buyPrice], [sellPrice] and [params].
  /// Returns null if either price is zero or negative.
  static MarginResult? compute({
    required double buyPrice,
    required double sellPrice,
    required MarginParams params,
  }) {
    if (buyPrice <= 0 || sellPrice <= 0) return null;

    final sellBrokerFee = sellPrice * params.brokerFeePct / 100;
    final salesTax = sellPrice * params.salesTaxPct / 100;
    final buyBrokerFee = params.includeBuyBrokerFee
        ? buyPrice * params.brokerFeePct / 100
        : 0.0;

    final profit = sellPrice - buyPrice - sellBrokerFee - salesTax - buyBrokerFee;
    final margin = profit / sellPrice * 100;

    // Break-even: sell = buyPrice / (1 - brokerFeePct/100 - salesTaxPct/100 [- brokerFeePct/100 if buy-side])
    // At break-even: sell - buy - sell*fee - sell*tax - buy*fee = 0
    // sell*(1 - fee - tax) = buy + buy*fee  (if includeBuyBrokerFee)
    // sell = (buy*(1 + buyFeeRate)) / (1 - sellFeeRate - taxRate)
    final sellFeeRate = params.brokerFeePct / 100 + params.salesTaxPct / 100;
    final buyFeeMultiplier =
        params.includeBuyBrokerFee ? 1 + params.brokerFeePct / 100 : 1.0;
    final denominator = 1 - sellFeeRate;
    final breakEven = denominator <= 0
        ? double.infinity
        : buyPrice * buyFeeMultiplier / denominator;

    return MarginResult(
      buyPrice: buyPrice,
      sellPrice: sellPrice,
      brokerFee: sellBrokerFee,
      salesTax: salesTax,
      buyBrokerFee: buyBrokerFee,
      profit: profit,
      margin: margin,
      breakEvenSellPrice: breakEven,
      orderOneSellPrice: sellPrice - 0.01,
    );
  }
}
