import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../alerts/price_alert_service.dart';
import '../database/app_database.dart';
import '../esi/esi_provider.dart';
import '../database/database_provider.dart';

final _priceAlertServiceProvider = Provider<PriceAlertService>((ref) {
  final db = ref.watch(databaseProvider);
  return PriceAlertService(
    db: db,
    esi: ref.watch(esiClientProvider),
  );
});

final priceAlertsStreamProvider = StreamProvider<List<PriceAlert>>((ref) {
  return ref.watch(_priceAlertServiceProvider).watchAlerts();
});
