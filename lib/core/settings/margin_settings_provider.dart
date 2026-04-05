import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../database/database_provider.dart';
import '../margin/margin_calculator.dart';

// Setting keys
const _kBrokerFeePct = 'broker_fee_pct';
const _kSalesTaxPct = 'sales_tax_pct';
const _kBuyBrokerFee = 'include_buy_broker_fee';
const _kMarketLogDir = 'market_log_dir';

/// Persisted Margin Tool parameters.
class MarginSettings {
  final double brokerFeePct;
  final double salesTaxPct;
  final bool includeBuyBrokerFee;
  final String? marketLogDir;

  const MarginSettings({
    this.brokerFeePct = 2.0,
    this.salesTaxPct = 3.6,
    this.includeBuyBrokerFee = false,
    this.marketLogDir,
  });

  MarginParams get marginParams => MarginParams(
        brokerFeePct: brokerFeePct,
        salesTaxPct: salesTaxPct,
        includeBuyBrokerFee: includeBuyBrokerFee,
      );

  MarginSettings copyWith({
    double? brokerFeePct,
    double? salesTaxPct,
    bool? includeBuyBrokerFee,
    String? marketLogDir,
    bool clearMarketLogDir = false,
  }) =>
      MarginSettings(
        brokerFeePct: brokerFeePct ?? this.brokerFeePct,
        salesTaxPct: salesTaxPct ?? this.salesTaxPct,
        includeBuyBrokerFee: includeBuyBrokerFee ?? this.includeBuyBrokerFee,
        marketLogDir:
            clearMarketLogDir ? null : (marketLogDir ?? this.marketLogDir),
      );
}

final marginSettingsProvider =
    AsyncNotifierProvider<MarginSettingsNotifier, MarginSettings>(
  MarginSettingsNotifier.new,
);

class MarginSettingsNotifier extends AsyncNotifier<MarginSettings> {
  @override
  Future<MarginSettings> build() async {
    final db = ref.watch(databaseProvider);
    final brokerFee =
        double.tryParse(await db.getSetting(_kBrokerFeePct) ?? '') ?? 2.0;
    final salesTax =
        double.tryParse(await db.getSetting(_kSalesTaxPct) ?? '') ?? 3.6;
    final buyFee = (await db.getSetting(_kBuyBrokerFee)) == '1';
    final logDir = await db.getSetting(_kMarketLogDir);
    return MarginSettings(
      brokerFeePct: brokerFee,
      salesTaxPct: salesTax,
      includeBuyBrokerFee: buyFee,
      marketLogDir: logDir?.isEmpty == true ? null : logDir,
    );
  }

  Future<void> save(MarginSettings s) async {
    final db = ref.read(databaseProvider);
    await Future.wait([
      db.setSetting(_kBrokerFeePct, s.brokerFeePct.toString()),
      db.setSetting(_kSalesTaxPct, s.salesTaxPct.toString()),
      db.setSetting(_kBuyBrokerFee, s.includeBuyBrokerFee ? '1' : '0'),
      db.setSetting(_kMarketLogDir, s.marketLogDir ?? ''),
    ]);
    state = AsyncData(s);
  }
}
