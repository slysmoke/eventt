import 'dart:async';
import 'dart:convert';
import 'dart:io';
import 'dart:math';

import 'package:dio/dio.dart';
import 'package:url_launcher/url_launcher.dart';

import 'pkce.dart';
import 'token_storage.dart';

/// EVE SSO OAuth 2.0 with PKCE + client secret (confidential client).
///
/// Registered callback: http://localhost:8000/callback
///
/// Flow:
/// 1. Open browser → EVE SSO authorization URL
/// 2. Listen on localhost:8000/callback for auth code
/// 3. Exchange code + PKCE verifier for tokens (HTTP Basic Auth with client secret)
/// 4. Verify token → get character ID
/// 5. Save tokens to [TokenStorage]
class EveAuthService {
  static const _ssoBase = 'https://login.eveonline.com/v2/oauth';

  // Must match the registered callback URL exactly
  static const callbackPort = 8000;
  static const _redirectUri = 'http://localhost:$callbackPort/callback';

  static const scopes = [
    'esi-wallet.read_character_wallet.v1',
    'esi-wallet.read_corporation_wallet.v1',
    'esi-wallet.read_corporation_wallets.v1',
    'esi-assets.read_assets.v1',
    'esi-assets.read_corporation_assets.v1',
    'esi-markets.read_character_orders.v1',
    'esi-markets.read_corporation_orders.v1',
    'esi-markets.structure_markets.v1',
    'esi-ui.open_window.v1',
    'esi-ui.write_waypoint.v1',
    'esi-characters.read_corporation_roles.v1',
    'esi-characters.read_contacts.v1',
    'esi-characters.read_standings.v1',
    'esi-characters.read_notifications.v1',
    'esi-characters.read_blueprints.v1',
    'esi-characters.read_titles.v1',
    'esi-characters.read_medals.v1',
    'esi-characters.read_loyalty.v1',
    'esi-characters.read_fatigue.v1',
    'esi-characters.read_fw_stats.v1',
    'esi-industry.read_character_jobs.v1',
    'esi-industry.read_character_mining.v1',
    'esi-industry.read_corporation_jobs.v1',
    'esi-industry.read_corporation_mining.v1',
    'esi-contracts.read_character_contracts.v1',
    'esi-contracts.read_corporation_contracts.v1',
    'esi-corporations.read_corporation_membership.v1',
    'esi-corporations.read_structures.v1',
    'esi-corporations.read_standings.v1',
    'esi-corporations.read_contacts.v1',
    'esi-corporations.read_divisions.v1',
    'esi-corporations.read_blueprints.v1',
    'esi-corporations.read_titles.v1',
    'esi-corporations.read_medals.v1',
    'esi-corporations.read_container_logs.v1',
    'esi-corporations.read_starbases.v1',
    'esi-corporations.read_facilities.v1',
    'esi-corporations.read_fw_stats.v1',
    'esi-corporations.track_members.v1',
    'esi-location.read_location.v1',
    'esi-location.read_ship_type.v1',
    'esi-location.read_online.v1',
    'esi-skills.read_skills.v1',
    'esi-skills.read_skillqueue.v1',
    'esi-clones.read_clones.v1',
    'esi-clones.read_implants.v1',
    'esi-fittings.read_fittings.v1',
    'esi-fittings.write_fittings.v1',
    'esi-killmails.read_killmails.v1',
    'esi-killmails.read_corporation_killmails.v1',
    'esi-planets.manage_planets.v1',
    'esi-planets.read_customs_offices.v1',
    'esi-fleets.read_fleet.v1',
    'esi-fleets.write_fleet.v1',
    'esi-universe.read_structures.v1',
    'esi-search.search_structures.v1',
    'esi-alliances.read_contacts.v1',
    'esi-mail.read_mail.v1',
    'esi-mail.send_mail.v1',
    'esi-mail.organize_mail.v1',
    'esi-calendar.read_calendar_events.v1',
    'esi-calendar.respond_calendar_events.v1',
    'publicData',
  ];

  final String clientId;
  final String clientSecret;
  final Dio _dio;
  final TokenStorage _tokenStorage;

  EveAuthService({
    required this.clientId,
    required this.clientSecret,
    Dio? dio,
    TokenStorage? tokenStorage,
  })  : _dio = dio ??
            (Dio()..interceptors.add(LogInterceptor(requestBody: true, responseBody: true))),
        _tokenStorage = tokenStorage ?? TokenStorage();

  /// Launches browser, awaits OAuth callback, exchanges tokens,
  /// saves them, and returns the authenticated EVE character ID.
  Future<int> authenticate() async {
    if (clientId.isEmpty) {
      throw StateError(
        'EVE_CLIENT_ID is not set.\n'
        'Run with: flutter run -d linux --dart-define-from-file=dart_defines.json',
      );
    }
    final pkce = Pkce.generate();
    final state = _randomState();

    final authUri = Uri.parse('$_ssoBase/authorize').replace(
      queryParameters: {
        'response_type': 'code',
        'client_id': clientId,
        'redirect_uri': _redirectUri,
        'scope': scopes.join(' '),
        'state': state,
        'code_challenge': pkce.codeChallenge,
        'code_challenge_method': 'S256',
      },
    );

    final code = await _waitForCallback(authUri, state);
    final tokens = await _exchangeCode(code, pkce.codeVerifier);
    final characterId = _extractCharacterIdFromJwt(tokens['access_token'] as String);

    await _tokenStorage.saveTokens(
      characterId: characterId,
      accessToken: tokens['access_token'] as String,
      refreshToken: tokens['refresh_token'] as String,
      expiresAt: DateTime.now().add(
        Duration(seconds: (tokens['expires_in'] as num).toInt()),
      ),
    );

    return characterId;
  }

  /// Returns a valid access token, refreshing if needed.
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
      '$_ssoBase/token',
      data: {
        'grant_type': 'refresh_token',
        'refresh_token': refreshToken,
      },
      options: Options(
        contentType: Headers.formUrlEncodedContentType,
        headers: clientSecret.isNotEmpty
            ? {'Authorization': _basicAuth()}
            : {'client_id': clientId},
      ),
    );

    final data = response.data as Map<String, dynamic>;
    await _tokenStorage.saveTokens(
      characterId: characterId,
      accessToken: data['access_token'] as String,
      refreshToken: data['refresh_token'] as String,
      expiresAt: DateTime.now().add(
        Duration(seconds: (data['expires_in'] as num).toInt()),
      ),
    );
  }

  Future<String> _waitForCallback(Uri authUri, String expectedState) async {
    final completer = Completer<String>();
    final server = await HttpServer.bind(InternetAddress.loopbackIPv4, callbackPort);

    unawaited(launchUrl(authUri, mode: LaunchMode.externalApplication));

    server.first.then((request) async {
      final params = request.uri.queryParameters;
      request.response
        ..statusCode = 200
        ..headers.contentType = ContentType.html
        ..write('<html><body>'
            '<h2>Authentication successful</h2>'
            '<p>You can close this tab and return to EVE NTT.</p>'
            '</body></html>');
      await request.response.close();
      await server.close();

      final error = params['error'];
      final code = params['code'];
      final state = params['state'];

      if (error != null) {
        completer.completeError(Exception('EVE SSO error: $error'));
      } else if (state != expectedState) {
        completer.completeError(Exception('OAuth state mismatch — possible CSRF'));
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
        throw TimeoutException('EVE SSO authentication timed out after 5 minutes');
      },
    );
  }

  String _basicAuth() {
    final credentials = base64.encode(utf8.encode('$clientId:$clientSecret'));
    return 'Basic $credentials';
  }

  Future<Map<String, dynamic>> _exchangeCode(
    String code,
    String codeVerifier,
  ) async {
    final response = await _dio.post(
      '$_ssoBase/token',
      data: {
        'grant_type': 'authorization_code',
        'code': code,
        'redirect_uri': _redirectUri,
        'code_verifier': codeVerifier,
      },
      options: Options(
        contentType: Headers.formUrlEncodedContentType,
        headers: clientSecret.isNotEmpty
            ? {'Authorization': _basicAuth()}
            : {'client_id': clientId},
      ),
    );
    return response.data as Map<String, dynamic>;
  }

  /// Extracts character ID from the EVE SSO JWT without a network call.
  /// JWT sub field format: "CHARACTER:EVE:{characterId}"
  int _extractCharacterIdFromJwt(String accessToken) {
    final parts = accessToken.split('.');
    if (parts.length != 3) throw StateError('Invalid JWT format');

    var payload = parts[1];
    // Restore base64url padding
    switch (payload.length % 4) {
      case 2:
        payload += '==';
      case 3:
        payload += '=';
    }

    final decoded = utf8.decode(base64Url.decode(payload));
    final data = jsonDecode(decoded) as Map<String, dynamic>;
    final sub = data['sub'] as String; // "CHARACTER:EVE:12345678"
    return int.parse(sub.split(':').last);
  }

  static String _randomState() {
    final rng = Random.secure();
    final bytes = List<int>.generate(16, (_) => rng.nextInt(256));
    return base64Url.encode(bytes).replaceAll('=', '');
  }
}
