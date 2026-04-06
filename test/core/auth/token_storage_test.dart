import 'package:flutter_secure_storage/flutter_secure_storage.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:mocktail/mocktail.dart';

import 'package:eve_ntt/core/auth/token_storage.dart';

class _MockSecureStorage extends Mock implements FlutterSecureStorage {}

void main() {
  late _MockSecureStorage storage;
  late TokenStorage tokenStorage;

  const characterId = 12345;

  setUp(() {
    storage = _MockSecureStorage();
    tokenStorage = TokenStorage(storage: storage);
  });

  group('TokenStorage', () {
    test('saves all three token fields', () async {
      when(() => storage.write(key: any(named: 'key'), value: any(named: 'value')))
          .thenAnswer((_) async {});

      await tokenStorage.saveTokens(
        characterId: characterId,
        accessToken: 'access_abc',
        refreshToken: 'refresh_xyz',
        expiresAt: DateTime(2030),
      );

      verify(() => storage.write(
            key: 'access_token_$characterId',
            value: 'access_abc',
          )).called(1);
      verify(() => storage.write(
            key: 'refresh_token_$characterId',
            value: 'refresh_xyz',
          )).called(1);
      verify(() => storage.write(
            key: 'token_expiry_$characterId',
            value: any(named: 'value'),
          )).called(1);
    });

    test('getAccessToken returns stored token', () async {
      when(() => storage.read(key: 'access_token_$characterId'))
          .thenAnswer((_) async => 'access_abc');

      final result = await tokenStorage.getAccessToken(characterId);
      expect(result, 'access_abc');
    });

    test('getRefreshToken returns stored token', () async {
      when(() => storage.read(key: 'refresh_token_$characterId'))
          .thenAnswer((_) async => 'refresh_xyz');

      final result = await tokenStorage.getRefreshToken(characterId);
      expect(result, 'refresh_xyz');
    });

    test('isTokenExpired returns true when no expiry stored', () async {
      when(() => storage.read(key: 'token_expiry_$characterId'))
          .thenAnswer((_) async => null);

      expect(await tokenStorage.isTokenExpired(characterId), isTrue);
    });

    test('isTokenExpired returns false for token expiring in 1 hour', () async {
      final future = DateTime.now().add(const Duration(hours: 1));
      when(() => storage.read(key: 'token_expiry_$characterId'))
          .thenAnswer((_) async => future.toIso8601String());

      expect(await tokenStorage.isTokenExpired(characterId), isFalse);
    });

    test('isTokenExpired returns true for already-expired token', () async {
      final past = DateTime.now().subtract(const Duration(minutes: 5));
      when(() => storage.read(key: 'token_expiry_$characterId'))
          .thenAnswer((_) async => past.toIso8601String());

      expect(await tokenStorage.isTokenExpired(characterId), isTrue);
    });

    test('isTokenExpired returns true within 2-minute buffer', () async {
      // expires in 1 minute — within buffer → treated as expired
      final almostExpired = DateTime.now().add(const Duration(minutes: 1));
      when(() => storage.read(key: 'token_expiry_$characterId'))
          .thenAnswer((_) async => almostExpired.toIso8601String());

      expect(await tokenStorage.isTokenExpired(characterId), isTrue);
    });

    test('deleteTokens removes all three fields', () async {
      when(() => storage.delete(key: any(named: 'key')))
          .thenAnswer((_) async {});

      await tokenStorage.deleteTokens(characterId);

      verify(() => storage.delete(key: 'access_token_$characterId')).called(1);
      verify(() => storage.delete(key: 'refresh_token_$characterId')).called(1);
      verify(() => storage.delete(key: 'token_expiry_$characterId')).called(1);
    });
  });
}
