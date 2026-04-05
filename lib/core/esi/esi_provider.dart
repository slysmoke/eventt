import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../database/database_provider.dart';
import 'esi_client.dart';
import 'interceptors/cache_interceptor.dart';

final esiClientProvider = Provider<EsiClient>((ref) {
  final db = ref.watch(databaseProvider);
  return EsiClient(cacheInterceptor: CacheInterceptor(db));
});
