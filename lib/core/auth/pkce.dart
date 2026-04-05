import 'dart:convert';
import 'dart:math';

import 'package:crypto/crypto.dart';

/// Generates a PKCE code verifier and challenge pair (RFC 7636, S256 method).
class Pkce {
  final String codeVerifier;
  final String codeChallenge;

  Pkce._(this.codeVerifier, this.codeChallenge);

  factory Pkce.generate() {
    final verifier = _generateVerifier();
    final challenge = _deriveChallenge(verifier);
    return Pkce._(verifier, challenge);
  }

  static String _generateVerifier() {
    const chars =
        'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~';
    final rng = Random.secure();
    return List.generate(128, (_) => chars[rng.nextInt(chars.length)]).join();
  }

  static String _deriveChallenge(String verifier) {
    final bytes = utf8.encode(verifier);
    final digest = sha256.convert(bytes);
    // base64url without padding
    return base64Url.encode(digest.bytes).replaceAll('=', '');
  }
}
