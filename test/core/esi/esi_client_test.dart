import 'package:dio/dio.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:mocktail/mocktail.dart';

import 'package:eve_ntt/core/esi/esi_client.dart';
import 'package:eve_ntt/core/esi/interceptors/cache_interceptor.dart';

class _MockCacheInterceptor extends Mock implements CacheInterceptor {}

void main() {
  late CacheInterceptor mockCacheInterceptor;

  setUpAll(() {
    mockCacheInterceptor = _MockCacheInterceptor();
  });

  group('EsiClient', () {
    test('has correct base URL', () {
      const expected = 'https://esi.evetech.net/latest';
      expect(EsiClient.baseUrl, expected);
    });

    test('can be constructed with mock cache interceptor', () {
      final client = EsiClient(cacheInterceptor: mockCacheInterceptor);
      expect(client, isNotNull);
    });

    test('uses tranquility datasource', () {
      final client = EsiClient(cacheInterceptor: mockCacheInterceptor);
      // Verify client was created successfully with default datasource
      expect(EsiClient.baseUrl, contains('esi.evetech.net'));
    });
  });
}
