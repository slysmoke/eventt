import 'package:dio/dio.dart';

import 'interceptors/cache_interceptor.dart';

/// Low-level ESI HTTP client.
///
/// Handles base URL, authorization header, and caching via [CacheInterceptor].
/// For authenticated endpoints pass [accessToken] per-request.
class EsiClient {
  static const baseUrl = 'https://esi.evetech.net/latest';
  static const _datasource = 'tranquility';

  final Dio _dio;

  EsiClient({required CacheInterceptor cacheInterceptor, Dio? dio})
      : _dio = dio ??
            Dio(BaseOptions(
              baseUrl: baseUrl,
              queryParameters: {'datasource': _datasource},
              responseType: ResponseType.json,
              // Accept 200-304; cache interceptor handles 304
              validateStatus: (s) => s != null && s < 500,
            )) {
    // Log all requests and responses for debugging
    _dio.interceptors.add(LogInterceptor(
      request: true,
      requestHeader: true,
      requestBody: true,
      responseHeader: true,
      responseBody: true,
      error: true,
      logPrint: (obj) => print('[ESI] $obj'),
    ));
    _dio.interceptors.add(cacheInterceptor);
  }

  Future<Response<dynamic>> get(
    String path, {
    Map<String, dynamic>? queryParameters,
    String? accessToken,
  }) {
    return _dio.get(
      path,
      queryParameters: queryParameters,
      options: _options(accessToken),
    );
  }

  Future<Response<dynamic>> post(
    String path, {
    dynamic data,
    Map<String, dynamic>? queryParameters,
    String? accessToken,
  }) {
    return _dio.post(
      path,
      data: data,
      queryParameters: queryParameters,
      options: _options(accessToken).copyWith(
        contentType: 'application/json',
      ),
    );
  }

  Options _options(String? accessToken) => Options(
        headers: accessToken != null
            ? {'Authorization': 'Bearer $accessToken'}
            : null,
      );
}
