import 'package:dio/dio.dart';
import 'package:drift/drift.dart' show Value;
import 'package:drift/native.dart';
import 'package:flutter_test/flutter_test.dart';

import 'package:eve_ntt/core/database/app_database.dart';
import 'package:eve_ntt/core/esi/interceptors/cache_interceptor.dart';

// Minimal Dio adapter that records the last request and returns a preset response.
class _FakeAdapter implements HttpClientAdapter {
  Response<dynamic>? nextResponse;
  RequestOptions? lastRequest;

  @override
  Future<ResponseBody> fetch(
    RequestOptions options,
    Stream<List<int>>? requestStream,
    Future<void>? cancelFuture,
  ) async {
    lastRequest = options;
    final r = nextResponse!;
    return ResponseBody.fromString(
      r.data as String,
      r.statusCode!,
      headers: r.headers.map,
    );
  }

  @override
  void close({bool force = false}) {}
}

void main() {
  late AppDatabase db;
  late CacheInterceptor interceptor;
  late Dio dio;
  late _FakeAdapter adapter;

  const testUrl = 'https://esi.evetech.net/latest/characters/12345/';

  setUp(() {
    db = AppDatabase.forTesting(NativeDatabase.memory());
    interceptor = CacheInterceptor(db);
    adapter = _FakeAdapter();

    dio = Dio(BaseOptions(
      baseUrl: 'https://esi.evetech.net',
      // Accept 200-304 so the interceptor sees 304 in onResponse
      validateStatus: (s) => s != null && s < 500,
    ));
    dio.httpClientAdapter = adapter;
    dio.interceptors.add(interceptor);
  });

  tearDown(() => db.close());

  group('CacheInterceptor', () {
    test('cache miss — request passes through to network', () async {
      adapter.nextResponse = Response(
        requestOptions: RequestOptions(path: testUrl),
        statusCode: 200,
        data: '{"name":"Pilot"}',
        headers: Headers.fromMap({
          'expires': [
            'Thu, 01 Jan 2030 00:00:00 GMT',
          ],
        }),
      );

      final response = await dio.get(testUrl);
      expect(response.statusCode, 200);
      expect(adapter.lastRequest, isNotNull);
    });

    test('stores response in cache after network call', () async {
      adapter.nextResponse = Response(
        requestOptions: RequestOptions(path: testUrl),
        statusCode: 200,
        data: '{"name":"Pilot"}',
        headers: Headers.fromMap({
          'expires': ['Thu, 01 Jan 2030 00:00:00 GMT'],
          'etag': ['W/"abc"'],
        }),
      );

      await dio.get(testUrl);

      final cached = await (db.select(db.esiCache)
            ..where((t) => t.url.equals(testUrl)))
          .getSingleOrNull();

      expect(cached, isNotNull);
      expect(cached!.etag, 'W/"abc"');
    });

    test('returns cached response without hitting network on cache hit', () async {
      // Pre-populate cache with a far-future expiry
      await db.into(db.esiCache).insert(EsiCacheCompanion.insert(
            url: testUrl,
            etag: const Value('W/"cached"'),
            expiresAt: DateTime(2099),
            cachedAt: DateTime.now(),
            body: '{"name":"CachedPilot"}',
          ));

      final response = await dio.get(testUrl);

      expect(response.data, {'name': 'CachedPilot'});
      // Adapter should NOT have been called
      expect(adapter.lastRequest, isNull);
    });

    test('adds If-None-Match header when cache is stale but has etag', () async {
      // Cache entry that is expired
      await db.into(db.esiCache).insert(EsiCacheCompanion.insert(
            url: testUrl,
            etag: const Value('W/"stale-etag"'),
            expiresAt: DateTime(2000), // past
            cachedAt: DateTime.now(),
            body: '{"name":"StaleData"}',
          ));

      adapter.nextResponse = Response(
        requestOptions: RequestOptions(path: testUrl),
        statusCode: 304,
        data: '',
        headers: Headers.fromMap({
          'expires': ['Thu, 01 Jan 2030 00:00:00 GMT'],
        }),
      );

      final response = await dio.get(testUrl);

      expect(adapter.lastRequest!.headers['If-None-Match'], 'W/"stale-etag"');
      // Should return stale cached body on 304
      expect(response.data, {'name': 'StaleData'});
    });

    test('returns stale cache on network error', () async {
      await db.into(db.esiCache).insert(EsiCacheCompanion.insert(
            url: testUrl,
            expiresAt: DateTime(2000), // expired
            cachedAt: DateTime.now(),
            body: '{"name":"StaleOnError"}',
          ));

      // Simulate network failure
      adapter.nextResponse = null;

      // Override adapter to throw
      dio.httpClientAdapter = _ThrowingAdapter();

      final response = await dio.get(testUrl);
      expect(response.data, {'name': 'StaleOnError'});
    });
  });
}

class _ThrowingAdapter implements HttpClientAdapter {
  @override
  Future<ResponseBody> fetch(
    RequestOptions options,
    Stream<List<int>>? requestStream,
    Future<void>? cancelFuture,
  ) =>
      Future.error(DioException(
        requestOptions: options, // must carry the real options so onError sees the correct URL
        type: DioExceptionType.connectionError,
      ));

  @override
  void close({bool force = false}) {}
}
