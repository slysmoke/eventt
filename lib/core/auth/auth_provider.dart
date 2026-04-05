import 'package:flutter_riverpod/flutter_riverpod.dart';

import 'eve_auth_service.dart';
import 'token_storage.dart';

final tokenStorageProvider = Provider<TokenStorage>((ref) => TokenStorage());

final eveAuthServiceProvider = Provider<EveAuthService>((ref) {
  const clientId = String.fromEnvironment('EVE_CLIENT_ID');
  return EveAuthService(
    clientId: clientId,
    tokenStorage: ref.watch(tokenStorageProvider),
  );
});

/// Global active character ID. null = no character selected.
///
/// Use `ref.read(activeCharacterIdProvider.notifier).state = id` to change.
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
