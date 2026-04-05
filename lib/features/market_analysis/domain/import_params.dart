/// Which order book side to use for pricing.
///
/// - [sell] = look at sell orders (buy from existing sell orders, or undercut them)
/// - [buy]  = look at buy orders (sell to existing buy orders, or outbid them)
enum PriceType { sell, buy }

/// Parameters controlling import margin computation.
class ImportParams {
  /// Price type for source station: how you acquire items there.
  /// Default: [PriceType.sell] — you buy from existing sell orders.
  final PriceType srcPriceType;

  /// Price type for destination station: how you sell items there.
  /// Default: [PriceType.buy] — you sell to existing buy orders immediately.
  final PriceType dstPriceType;

  /// ISK per m³ of item volume (transport cost).
  final double pricePerM3;

  /// Collateral as % of [collateralPriceType] price (insurance/risk cost).
  final double collateralPct;

  /// Which price to base the collateral calculation on.
  /// Default: [PriceType.buy] — based on destination buy order price.
  final PriceType collateralPriceType;

  /// Source price modifier in percent (e.g. +5 adds 5% to source price).
  final double srcPriceMod;

  /// Only include rows with margin ≥ this value (%).
  final double minMarginPct;

  /// Only include rows with margin ≤ this value (%). Null = no upper limit.
  final double? maxMarginPct;

  /// If true, hide types that have no sell orders at the source station.
  final bool hideEmptySrcSell;

  /// Percentile for volume-weighted pricing (Evernus-style).
  /// Default: 0.05 (5%) — price at which 5% of total volume is traded.
  /// Set to 0 to use best price only.
  final double volumePercentile;

  /// Days of history to analyze (for volume projections).
  /// Currently unused until market history integration.
  final int analysisDays;

  /// Days to aggregate volume for projected sales estimates.
  /// Currently unused until market history integration.
  final int aggrDays;

  const ImportParams({
    this.srcPriceType = PriceType.sell,
    this.dstPriceType = PriceType.buy,
    this.pricePerM3 = 0,
    this.collateralPct = 0,
    this.collateralPriceType = PriceType.buy,
    this.srcPriceMod = 0,
    this.minMarginPct = 0,
    this.maxMarginPct,
    this.hideEmptySrcSell = false,
    this.volumePercentile = 0, // 0 = best price only (current behavior)
    this.analysisDays = 180,
    this.aggrDays = 7,
  });

  ImportParams copyWith({
    PriceType? srcPriceType,
    PriceType? dstPriceType,
    double? pricePerM3,
    double? collateralPct,
    PriceType? collateralPriceType,
    double? srcPriceMod,
    double? minMarginPct,
    double? maxMarginPct,
    bool? hideEmptySrcSell,
    double? volumePercentile,
    int? analysisDays,
    int? aggrDays,
    bool clearMaxMargin = false,
  }) =>
      ImportParams(
        srcPriceType: srcPriceType ?? this.srcPriceType,
        dstPriceType: dstPriceType ?? this.dstPriceType,
        pricePerM3: pricePerM3 ?? this.pricePerM3,
        collateralPct: collateralPct ?? this.collateralPct,
        collateralPriceType: collateralPriceType ?? this.collateralPriceType,
        srcPriceMod: srcPriceMod ?? this.srcPriceMod,
        minMarginPct: minMarginPct ?? this.minMarginPct,
        maxMarginPct: clearMaxMargin ? null : (maxMarginPct ?? this.maxMarginPct),
        hideEmptySrcSell: hideEmptySrcSell ?? this.hideEmptySrcSell,
        volumePercentile: volumePercentile ?? this.volumePercentile,
        analysisDays: analysisDays ?? this.analysisDays,
        aggrDays: aggrDays ?? this.aggrDays,
      );
}
