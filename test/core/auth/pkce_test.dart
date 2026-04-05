import 'dart:convert';

import 'package:crypto/crypto.dart';
import 'package:flutter_test/flutter_test.dart';

import 'package:eventt/core/auth/pkce.dart';

void main() {
  group('Pkce', () {
    test('generates a code verifier of valid length', () {
      final pkce = Pkce.generate();
      // RFC 7636: verifier must be 43-128 chars
      expect(pkce.codeVerifier.length, greaterThanOrEqualTo(43));
      expect(pkce.codeVerifier.length, lessThanOrEqualTo(128));
    });

    test('verifier contains only unreserved URI characters', () {
      final pkce = Pkce.generate();
      final validChars = RegExp(r'^[A-Za-z0-9\-._~]+$');
      expect(validChars.hasMatch(pkce.codeVerifier), isTrue);
    });

    test('challenge is base64url-encoded sha256 of verifier', () {
      final pkce = Pkce.generate();
      final bytes = utf8.encode(pkce.codeVerifier);
      final digest = sha256.convert(bytes);
      final expected = base64Url.encode(digest.bytes).replaceAll('=', '');
      expect(pkce.codeChallenge, expected);
    });

    test('each call generates a unique verifier', () {
      final a = Pkce.generate();
      final b = Pkce.generate();
      expect(a.codeVerifier, isNot(b.codeVerifier));
    });

    test('challenge has no padding characters', () {
      final pkce = Pkce.generate();
      expect(pkce.codeChallenge.contains('='), isFalse);
    });
  });
}
