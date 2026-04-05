import 'dart:async';
import 'dart:convert';
import 'dart:io';
import 'dart:math';

import 'package:dio/dio.dart';
import 'package:url_launcher/url_launcher.dart';

import 'pkce.dart';
import 'token_storage.dart';

/// EVE SSO OAuth 2.0 with PKCE.
///
/// Flow:
/// 1. Open browser with EVE SSO authorization URL
/// 2. Capture auth code via local HTTP server on localhost
/// 3. Exchange code + verifier for tokens
/// 4. Verify token → get character ID
/// 5. Save tokens to [TokenStorage]
class EveAuthService {
  static const _ssoBaseUrl = 'https://login.eveonline.com/v2/oauth';
  static const _verifyUrl = 'https://esi.evetech.net/verify/';
  static const _callbackPath = '/eve_callback';

  static const _scopes = [
    'esi-characters.read_portraits.v1',
    'esi-markets.read_character_orders.v1',
    'esi-wallet.read_character_wallet.v1',
    'esi-wallet.read_character_wallets.v1',
    'esi-assets.read_assets.v1',
    'esi-ui.open_window.v1',
    'esi-corporations.read_assets.v1',
    'esi-wallet.read_corporation_wallets.v1',
    'esi-markets.structure_markets.v1',
  ];

  final Dio _dio;
  final TokenStorage _tokenStorage;

  /// The EVE SSO Client ID — set via --dart-define=EVE_CLIENT_ID=...
  final String clientId;

  EveAuthService({
    required this.clientId,
    Dio? dio,
    TokenStorage? tokenStorage,
  })  : _dio = dio ?? Dio(),
        _tokenStorage = tokenStorage ?? TokenStorage();

  /// Launches the browser, waits for the OAuth callback, exchanges tokens,
  /// and returns the authenticated character ID.
  Future<int> authenticate() async {
    final pkce = Pkce.generate();
    final state = _randomState();

    final port = await _findFreePort();
    final redirectUri = 'http://localhost:$port$_callbackPath';

    final authUri = Uri.parse('$_ssoBaseUrl/authorize').replace(
      queryParameters: {
        'response_type': 'code',
        'client_id': clientId,
        'redirect_uri': redirectUri,
        'scope': _scopes.join(' '),
        'state': state,
        'code_challenge': pkce.codeChallenge,
        'code_challenge_method': 'S256',
      },
    );

    final code = await _waitForCallback(authUri, state, port, redirectUri);
    final tokens = await _exchangeCode(code, pkce.codeVerifier, redirectUri);
    final characterId = await _verifyAndGetCharacterId(tokens['access_token'] as String);

    await _tokenStorage.saveTokens(
      characterId: characterId,
      accessToken: tokens['access_token'] as String,
      refreshToken: tokens['refresh_token'] as String,
      expiresAt:
          DateTime.now().add(Duration(seconds: tokens['expires_in'] as int)),
    );

    return characterId;
  }

  /// Returns a valid access token for [characterId], refreshing if needed.
  Future<String> getValidAccessToken(int characterId) async {
    if (await _tokenStorage.isTokenExpired(characterId)) {
      await _refreshTokens(characterId);
    }
    return (await _tokenStorage.getAccessToken(characterId))!;
  }

  Future<void> removeCharacter(int characterId) =>
      _tokenStorage.deleteTokens(characterId);

  // ---------------------------------------------------------------------------

  Future<void> _refreshTokens(int characterId) async {
    final refreshToken = await _tokenStorage.getRefreshToken(characterId);
    if (refreshToken == null) {
      throw StateError('No refresh token for character $characterId');
    }

    final response = await _dio.post(
      '$_ssoBaseUrl/token',
      data: {
        'grant_type': 'refresh_token',
        'refresh_token': refreshToken,
        'client_id': clientId,
      },
      options: Options(contentType: Headers.formUrlEncodedContentType),
    );

    final data = response.data as Map<String, dynamic>;
    await _tokenStorage.saveTokens(
      characterId: characterId,
      accessToken: data['access_token'] as String,
      refreshToken: data['refresh_token'] as String,
      expiresAt:
          DateTime.now().add(Duration(seconds: data['expires_in'] as int)),
    );
  }

  Future<String> _waitForCallback(
    Uri authUri,
    String expectedState,
    int port,
    String redirectUri,
  ) async {
    final completer = Completer<String>();
    final server =
        await HttpServer.bind(InternetAddress.loopbackIPv4, port);

    unawaited(launchUrl(authUri, mode: LaunchMode.externalApplication));

    server.first.then((request) async {
      final params = request.uri.queryParameters;
      request.response
        ..statusCode = 200
        ..headers.contentType = ContentType.html
        ..write(
          '<html><body>'
          '<h2>Authentication successful</h2>'
          '<p>You can close this tab and return to eventt.</p>'
          '</body></html>',
        );
      await request.response.close();
      await server.close();

      final error = params['error'];
      final code = params['code'];
      final state = params['state'];

      if (error != null) {
        completer.completeError(Exception('EVE SSO error: $error'));
      } else if (state != expectedState) {
        completer.completeError(Exception('OAuth state mismatch'));
      } else if (code != null) {
        completer.complete(code);
      } else {
        completer.completeError(Exception('No authorization code received'));
      }
    });

    return completer.future.timeout(
      const Duration(minutes: 5),
      onTimeout: () {
        server.close();
        throw TimeoutException('EVE SSO authentication timed out');
      },
    );
  }

  Future<Map<String, dynamic>> _exchangeCode(
    String code,
    String codeVerifier,
    String redirectUri,
  ) async {
    final response = await _dio.post(
      '$_ssoBaseUrl/token',
      data: {
        'grant_type': 'authorization_code',
        'code': code,
        'redirect_uri': redirectUri,
        'client_id': clientId,
        'code_verifier': codeVerifier,
      },
      options: Options(contentType: Headers.formUrlEncodedContentType),
    );
    return response.data as Map<String, dynamic>;
  }

  Future<int> _verifyAndGetCharacterId(String accessToken) async {
    final response = await _dio.get(
      _verifyUrl,
      options: Options(headers: {'Authorization': 'Bearer $accessToken'}),
    );
    final data = response.data;
    // EVE verify endpoint returns CharacterID as integer
    return (data['CharacterID'] as num).toInt();
  }

  static String _randomState() {
    final rng = Random.secure();
    final bytes = List<int>.generate(16, (_) => rng.nextInt(256));
    return base64Url.encode(bytes).replaceAll('=', '');
  }

  static Future<int> _findFreePort() async {
    final server = await ServerSocket.bind(InternetAddress.loopbackIPv4, 0);
    final port = server.port;
    await server.close();
    return port;
  }
}
