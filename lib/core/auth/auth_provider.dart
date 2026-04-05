import 'package:flutter_riverpod/flutter_riverpod.dart';

import 'eve_auth_service.dart';
import 'token_storage.dart';

final tokenStorageProvider = Provider<TokenStorage>((ref) => TokenStorage());

final eveAuthServiceProvider = Provider<EveAuthService>((ref) {
  const clientId = String.fromEnvironment('EVE_CLIENT_ID');
  const clientSecret = String.fromEnvironment('EVE_CLIENT_SECRET');
  return EveAuthService(
    clientId: clientId,
    clientSecret: clientSecret,
    tokenStorage: ref.watch(tokenStorageProvider),
  );
});

/// Global active character ID. null = no character selected.
///
/// Use `ref.read(activeCharacterIdProvider.notifier).select(id)` to change.
final activeCharacterIdProvider =
    NotifierProvider<ActiveCharacterNotifier, int?>(
  ActiveCharacterNotifier.new,
);

class ActiveCharacterNotifier extends Notifier<int?> {
  @override
  int? build() => null;

  void select(int characterId) => state = characterId;
  void clear() => state = null;
}
