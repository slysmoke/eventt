import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../database/database_provider.dart';
import 'sde_updater.dart';

final sdeUpdaterProvider = Provider<SdeUpdater>((ref) {
  return SdeUpdater(db: ref.watch(databaseProvider));
});
