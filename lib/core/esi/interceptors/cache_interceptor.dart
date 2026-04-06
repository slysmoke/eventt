import 'dart:convert';
import 'dart:io' show HttpDate;

import 'package:dio/dio.dart';
import 'package:drift/drift.dart' show Value;

import '../../database/app_database.dart';

/// Dio interceptor that caches ESI GET responses in SQLite.
///
/// Cache strategy:
/// - Valid cache (not expired) → resolve immediately, skip network.
/// - Stale cache with ETag → add If-None-Match, go to network.
///   - 304 → refresh expiry, return cached body.
/// - Network error → return stale cache if available.
/// - 200 → store/update cache.
class CacheInterceptor extends Interceptor {
  final AppDatabase _db;

  CacheInterceptor(this._db);

  @override
  void onRequest(
    RequestOptions options,
    RequestInterceptorHandler handler,
  ) async {
    if (options.method != 'GET') {
      handler.next(options);
      return;
    }

    final url = options.uri.toString();
    final cached = await _getCached(url);

    if (cached != null) {
      // Validate cache body is proper JSON
      try {
        jsonDecode(cached.body);
      } catch (_) {
        // Bad cache entry (e.g., old .toString() format) — delete it
        await (_db.delete(_db.esiCache)..where((t) => t.url.equals(url))).go();
        handler.next(options);
        return;
      }

      if (cached.expiresAt.isAfter(DateTime.now())) {
        // Fresh cache — return without hitting network
        handler.resolve(
          _cachedResponse(options, cached.body),
          true,
        );
        return;
      }

      if (cached.etag != null) {
        options.headers['If-None-Match'] = cached.etag;
      }
    }

    handler.next(options);
  }

  @override
  void onResponse(Response response, ResponseInterceptorHandler handler) async {
    if (response.requestOptions.method == 'GET') {
      final url = response.requestOptions.uri.toString();

      if (response.statusCode == 200) {
        final expires = _parseExpires(response.headers);
        final etag = response.headers.value('etag');
        // Serialize to proper JSON string for storage
        final body = response.data is String
            ? response.data as String
            : jsonEncode(response.data);

        await _db.into(_db.esiCache).insertOnConflictUpdate(
              EsiCacheCompanion.insert(
                url: url,
                etag: Value(etag),
                expiresAt: expires,
                cachedAt: DateTime.now(),
                body: body,
              ),
            );
      } else if (response.statusCode == 304) {
        // ETag matched — refresh expiry, return cached body
        final expires = _parseExpires(response.headers);
        await (_db.update(_db.esiCache)..where((t) => t.url.equals(url)))
            .write(EsiCacheCompanion(
          expiresAt: Value(expires),
          cachedAt: Value(DateTime.now()),
        ));

        final cached = await _getCached(url);
        if (cached != null) {
          handler.resolve(_cachedResponse(response.requestOptions, cached.body));
          return;
        }
      }
    }

    handler.next(response);
  }

  @override
  void onError(DioException err, ErrorInterceptorHandler handler) async {
    if (err.requestOptions.method == 'GET') {
      final url = err.requestOptions.uri.toString();
      final cached = await _getCached(url);
      if (cached != null) {
        handler.resolve(
          _cachedResponse(err.requestOptions, cached.body, stale: true),
        );
        return;
      }
    }
    handler.next(err);
  }

  // ---------------------------------------------------------------------------

  Future<EsiCacheEntry?> _getCached(String url) {
    return (
      _db.select(_db.esiCache)..where((t) => t.url.equals(url))
    ).getSingleOrNull();
  }

  Response<dynamic> _cachedResponse(
    RequestOptions options,
    String body, {
    bool stale = false,
  }) {
    // Parse JSON string back to Map so Dio consumers get proper data
    dynamic parsedData;
    try {
      parsedData = jsonDecode(body);
    } catch (_) {
      // Fallback: return raw string if not valid JSON
      parsedData = body;
    }

    return Response(
      requestOptions: options,
      statusCode: 200,
      data: parsedData,
      headers: Headers.fromMap({
        'x-from-cache': ['true'],
        if (stale) 'x-stale': ['true'],
      }),
    );
  }

  /// Parses expiry from ESI response headers.
  /// Priority: Expires header → Cache-Control max-age → fallback 5 min.
  DateTime _parseExpires(Headers headers) {
    final expiresHeader = headers.value('expires');
    if (expiresHeader != null) {
      try {
        return HttpDate.parse(expiresHeader);
      } catch (_) {}
    }

    final cacheControl = headers.value('cache-control');
    if (cacheControl != null) {
      final match = RegExp(r'max-age=(\d+)').firstMatch(cacheControl);
      if (match != null) {
        final seconds = int.parse(match.group(1)!);
        return DateTime.now().add(Duration(seconds: seconds));
      }
    }

    return DateTime.now().add(const Duration(minutes: 5));
  }
}
