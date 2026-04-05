import 'package:flutter_secure_storage/flutter_secure_storage.dart';

/// Stores OAuth tokens per character in the platform secure storage.
///
/// - Linux: libsecret / GNOME Keyring (requires libsecret in shell.nix)
/// - macOS: Keychain
/// - Windows: Credential Manager
class TokenStorage {
  final FlutterSecureStorage _storage;

  TokenStorage({FlutterSecureStorage? storage})
      : _storage = storage ?? const FlutterSecureStorage();

  static String _accessKey(int id) => 'access_token_$id';
  static String _refreshKey(int id) => 'refresh_token_$id';
  static String _expiryKey(int id) => 'token_expiry_$id';

  Future<void> saveTokens({
    required int characterId,
    required String accessToken,
    required String refreshToken,
    required DateTime expiresAt,
  }) async {
    await Future.wait([
      _storage.write(key: _accessKey(characterId), value: accessToken),
      _storage.write(key: _refreshKey(characterId), value: refreshToken),
      _storage.write(
          key: _expiryKey(characterId), value: expiresAt.toIso8601String()),
    ]);
  }

  Future<String?> getAccessToken(int characterId) =>
      _storage.read(key: _accessKey(characterId));

  Future<String?> getRefreshToken(int characterId) =>
      _storage.read(key: _refreshKey(characterId));

  Future<DateTime?> getTokenExpiry(int characterId) async {
    final raw = await _storage.read(key: _expiryKey(characterId));
    return raw != null ? DateTime.parse(raw) : null;
  }

  /// Returns true if the token is expired or expires within 2 minutes.
  Future<bool> isTokenExpired(int characterId) async {
    final expiry = await getTokenExpiry(characterId);
    if (expiry == null) return true;
    return DateTime.now()
        .isAfter(expiry.subtract(const Duration(minutes: 2)));
  }

  Future<void> deleteTokens(int characterId) async {
    await Future.wait([
      _storage.delete(key: _accessKey(characterId)),
      _storage.delete(key: _refreshKey(characterId)),
      _storage.delete(key: _expiryKey(characterId)),
    ]);
  }
}
