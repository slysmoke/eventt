package org.eventt.core.auth

import com.sun.net.httpserver.HttpServer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.eventt.core.database.CharacterDao
import org.eventt.core.http.EveHttpClient
import org.eventt.core.model.CharacterModel
import java.awt.Desktop
import java.net.InetSocketAddress
import java.net.URI

private const val ESI_SSO_URL = "https://login.eveonline.com/v2/oauth"
private const val ESI_VERIFY_URL = "https://login.eveonline.com/oauth/verify"

@Serializable
data class TokenResponse(
    val access_token: String,
    val token_type: String = "Bearer",
    val expires_in: Int,
    val refresh_token: String,
)

object SsoAuthManager {
    // Public/native client (PKCE) — no client secret. An app registered as a "confidential"
    // client on developers.eveonline.com must be switched to PKCE/native for this to work.
    private const val CLIENT_ID = "9bacf8234c4b41888f00b084413868c0"
    private const val CALLBACK_URL = "http://localhost:8000/callback"
    private val SCOPES =
        listOf(
            "esi-calendar.respond_calendar_events.v1",
            "esi-calendar.read_calendar_events.v1",
            "esi-location.read_location.v1",
            "esi-location.read_ship_type.v1",
            "esi-mail.organize_mail.v1",
            "esi-mail.read_mail.v1",
            "esi-mail.send_mail.v1",
            "esi-skills.read_skills.v1",
            "esi-skills.read_skillqueue.v1",
            "esi-wallet.read_character_wallet.v1",
            "esi-wallet.read_corporation_wallet.v1",
            "esi-search.search_structures.v1",
            "esi-clones.read_clones.v1",
            "esi-characters.read_contacts.v1",
            "esi-universe.read_structures.v1",
            "esi-killmails.read_killmails.v1",
            "esi-corporations.read_corporation_membership.v1",
            "esi-assets.read_assets.v1",
            "esi-planets.manage_planets.v1",
            "esi-fleets.read_fleet.v1",
            "esi-fleets.write_fleet.v1",
            "esi-ui.open_window.v1",
            "esi-ui.write_waypoint.v1",
            "esi-characters.write_contacts.v1",
            "esi-markets.structure_markets.v1",
            "esi-corporations.read_structures.v1",
            "esi-characters.read_loyalty.v1",
            "esi-characters.read_chat_channels.v1",
            "esi-characters.read_medals.v1",
            "esi-characters.read_standings.v1",
            "esi-characters.read_agents_research.v1",
            "esi-industry.read_character_jobs.v1",
            "esi-markets.read_character_orders.v1",
            "esi-characters.read_blueprints.v1",
            "esi-characters.read_corporation_roles.v1",
            "esi-location.read_online.v1",
            "esi-contracts.read_character_contracts.v1",
            "esi-clones.read_implants.v1",
            "esi-characters.read_fatigue.v1",
            "esi-killmails.read_corporation_killmails.v1",
            "esi-corporations.track_members.v1",
            "esi-wallet.read_corporation_wallets.v1",
            "esi-characters.read_notifications.v1",
            "esi-corporations.read_divisions.v1",
            "esi-corporations.read_contacts.v1",
            "esi-assets.read_corporation_assets.v1",
            "esi-corporations.read_titles.v1",
            "esi-corporations.read_blueprints.v1",
            "esi-contracts.read_corporation_contracts.v1",
            "esi-corporations.read_standings.v1",
            "esi-corporations.read_starbases.v1",
            "esi-industry.read_corporation_jobs.v1",
            "esi-markets.read_corporation_orders.v1",
            "esi-corporations.read_container_logs.v1",
            "esi-industry.read_character_mining.v1",
            "esi-industry.read_corporation_mining.v1",
            "esi-planets.read_customs_offices.v1",
            "esi-corporations.read_facilities.v1",
            "esi-corporations.read_medals.v1",
            "esi-characters.read_titles.v1",
            "esi-alliances.read_contacts.v1",
            "esi-characters.read_fw_stats.v1",
            "esi-corporations.read_fw_stats.v1",
            "esi-corporations.read_projects.v1",
            "esi-corporations.read_freelance_jobs.v1",
            "esi-characters.read_freelance_jobs.v1",
            "publicData",
            "esi-fittings.read_fittings.v1",
            "esi-fittings.write_fittings.v1",
        ).joinToString(" ")

    private var server: HttpServer? = null

    @Volatile
    private var authResult: AuthResult? = null

    @Volatile
    private var codeVerifier: String? = null
    private val lock = Any()

    data class AuthResult(
        val success: Boolean,
        val character: CharacterModel? = null,
        val error: String? = null,
    )

    fun startAuth(): AuthResult {
        val state =
            java.util.UUID
                .randomUUID()
                .toString()
        val verifier = generateCodeVerifier()
        codeVerifier = verifier
        authResult = null

        // Properly encode for OAuth2: scope uses space-separated, redirect uses percent-encoding
        val encodedScopes = java.net.URLEncoder.encode(SCOPES, "UTF-8")
        val encodedRedirect = java.net.URLEncoder.encode(CALLBACK_URL, "UTF-8")
        val challenge = codeChallenge(verifier)
        val authUrl =
            "$ESI_SSO_URL/authorize?response_type=code&redirect_uri=$encodedRedirect&client_id=$CLIENT_ID" +
                "&scope=$encodedScopes&state=$state&code_challenge=$challenge&code_challenge_method=S256"

        println("[Auth] SSO URL: $ESI_SSO_URL/authorize")
        println("[Auth] Client ID: $CLIENT_ID")
        println("[Auth] Redirect URI: $CALLBACK_URL")
        println("[Auth] State: $state")
        println("[Auth] Scopes: ${SCOPES.split(" ").size} scopes")

        startCallbackServer()

        try {
            if (Desktop.isDesktopSupported()) {
                println("[Auth] Opening browser...")
                Desktop.getDesktop().browse(URI(authUrl))
            } else {
                println("[Auth] Desktop not supported. Open this URL in your browser:")
                println("[Auth] $authUrl")
            }
        } catch (e: Exception) {
            println("[Auth] Could not open browser: ${e.message}")
            println("[Auth] Open this URL manually: $authUrl")
        }

        // Wait for callback (max 5 minutes)
        var waited = 0L
        while (authResult == null && waited < 300_000) {
            Thread.sleep(500)
            waited += 500
            if (waited % 10000L == 0L) {
                println("[Auth] Waiting for browser callback... (${waited / 1000}s)")
            }
        }

        stopServer()

        return if (authResult != null) {
            authResult!!
        } else {
            println("[Auth] Auth timed out after 5 minutes")
            AuthResult(success = false, error = "Authentication timed out — browser may not have opened or callback blocked")
        }
    }

    fun refreshToken(refreshToken: String): TokenResponse? {
        val client = EveHttpClient.getClient()

        val encodedRefreshToken = java.net.URLEncoder.encode(refreshToken, "UTF-8")
        val body = "grant_type=refresh_token&refresh_token=$encodedRefreshToken&client_id=$CLIENT_ID"
        val requestBody = body.toRequestBody("application/x-www-form-urlencoded".toMediaType())

        val request =
            Request
                .Builder()
                .url("$ESI_SSO_URL/token")
                .post(requestBody)
                .header("Host", "login.eveonline.com")
                .build()

        return client.newCall(request).execute().use { response ->
            if (response.isSuccessful) {
                val json = Json { ignoreUnknownKeys = true }
                json.decodeFromString<TokenResponse>(response.body?.string() ?: "")
            } else {
                val errorBody = response.body?.string() ?: ""
                println("[Auth] Token refresh failed: ${response.code} — $errorBody")
                null
            }
        }
    }

    fun verifyToken(accessToken: String): CharacterInfo? {
        val client = EveHttpClient.getClient()

        val request =
            Request
                .Builder()
                .url(ESI_VERIFY_URL)
                .header("Authorization", "Bearer $accessToken")
                .build()

        return client.newCall(request).execute().use { response ->
            if (response.isSuccessful) {
                val json = Json { ignoreUnknownKeys = true }
                val body = response.body?.string() ?: return null
                runCatching {
                    json.decodeFromString<CharacterInfo>(body)
                }.getOrNull()
            } else {
                println("[Auth] Token verification failed: ${response.code}")
                null
            }
        }
    }

    fun ensureTokenFresh(characterId: Int): String? {
        val expiry = CharacterDao.getTokenExpiry(characterId)
        val now = System.currentTimeMillis()

        if (expiry - now > 60_000) {
            return CharacterDao.getAccessToken(characterId)
        }

        val refreshToken =
            runCatching {
                val char = CharacterDao.getById(characterId)
                char?.refreshToken
            }.getOrNull() ?: return null

        val tokenResponse = refreshToken(refreshToken) ?: return null

        val expiresAt = now + (tokenResponse.expires_in * 1000L)
        CharacterDao.updateToken(characterId, tokenResponse.access_token, expiresAt)
        CharacterDao.updateRefreshToken(characterId, tokenResponse.refresh_token)

        return tokenResponse.access_token
    }

    @Serializable
    data class CharacterInfo(
        val CharacterID: Int,
        val CharacterName: String,
        val ExpiresOn: String,
        val Scopes: String,
        val TokenType: String,
        val CharacterOwnerHash: String,
        val IntellectualProperty: String,
    )

    private fun startCallbackServer() {
        // Stop any existing server first
        stopServer()

        val srv = HttpServer.create(InetSocketAddress("127.0.0.1", 8000), 0)
        server = srv

        println("[Auth] Callback server started on http://127.0.0.1:8000")

        srv.createContext("/callback") { exchange ->
            val query = exchange.requestURI.query ?: ""
            val params = parseQueryString(query)

            println("[Auth] Callback received: $query")

            val error = params["error"]
            val code = params["code"]
            val state = params["state"]

            val responseHeaders = mapOf("Content-Type" to "text/html; charset=utf-8")

            if (error != null) {
                val html = "<html><body><h1>Authentication failed</h1><p>$error</p><p>You can close this window.</p></body></html>"
                sendResponse(exchange, 200, html, responseHeaders)
                synchronized(lock) {
                    authResult = AuthResult(success = false, error = "SSO error: $error")
                }
                println("[Auth] SSO error: $error")
                return@createContext
            }

            if (code != null) {
                println("[Auth] Received authorization code: ${code.take(10)}...")
                val character = exchangeCodeForToken(code)
                if (character != null) {
                    CharacterDao.insert(character)
                    val html =
                        "<html><body><h1>Authentication successful!</h1><p>Welcome, ${character.name}! " +
                            "You can close this window.</p></body></html>"
                    sendResponse(exchange, 200, html, responseHeaders)
                    synchronized(lock) {
                        authResult = AuthResult(success = true, character = character)
                    }
                    println("[Auth] Auth successful: ${character.name} (ID: ${character.id})")
                } else {
                    val html =
                        "<html><body><h1>Authentication failed</h1><p>Could not exchange token. The code may have " +
                            "been used already or is invalid.</p><p>You can close this window.</p></body></html>"
                    sendResponse(exchange, 200, html, responseHeaders)
                    synchronized(lock) {
                        authResult = AuthResult(success = false, error = "Token exchange failed — code invalid or expired")
                    }
                    println("[Auth] Token exchange failed for code: ${code.take(10)}...")
                }
            } else {
                val html =
                    "<html><body><h1>Authentication failed</h1><p>No authorization code received.</p>" +
                        "<p>You can close this window.</p></body></html>"
                sendResponse(exchange, 200, html, responseHeaders)
                synchronized(lock) {
                    authResult = AuthResult(success = false, error = "No authorization code in callback")
                }
                println("[Auth] No authorization code in callback")
            }
        }

        srv.setExecutor(
            java.util.concurrent.Executors
                .newSingleThreadExecutor(),
        )
        srv.start()
    }

    private fun stopServer() {
        server?.stop(1)
        server = null
    }

    private fun exchangeCodeForToken(code: String): CharacterModel? {
        val client = EveHttpClient.getClient()
        val verifier = codeVerifier

        if (verifier == null) {
            println("[Auth] No code_verifier for this session — was startAuth() called?")
            return null
        }

        val encodedRedirect = java.net.URLEncoder.encode(CALLBACK_URL, "UTF-8")
        val encodedCode = java.net.URLEncoder.encode(code, "UTF-8")
        val body =
            "grant_type=authorization_code&code=$encodedCode&redirect_uri=$encodedRedirect" +
                "&client_id=$CLIENT_ID&code_verifier=$verifier"

        println("[Auth] Exchanging code for token...")
        println("[Auth] Redirect URI (encoded): $encodedRedirect")

        val request =
            Request
                .Builder()
                .url("$ESI_SSO_URL/token")
                .post(body.toRequestBody("application/x-www-form-urlencoded".toMediaType()))
                .header("Host", "login.eveonline.com")
                .header("Content-Type", "application/x-www-form-urlencoded")
                .build()

        return client.newCall(request).execute().use { response ->
            val responseBody = response.body?.string() ?: ""
            if (response.isSuccessful) {
                println("[Auth] Token exchange successful")
                val json = Json { ignoreUnknownKeys = true }
                val tokenResponse = json.decodeFromString<TokenResponse>(responseBody)

                val charInfo = verifyToken(tokenResponse.access_token)
                if (charInfo != null) {
                    val expiresAt = System.currentTimeMillis() + (tokenResponse.expires_in * 1000L)
                    println("[Auth] Character: ${charInfo.CharacterName} (ID: ${charInfo.CharacterID})")
                    CharacterModel(
                        id = charInfo.CharacterID,
                        name = charInfo.CharacterName,
                        refreshToken = tokenResponse.refresh_token,
                        accessToken = tokenResponse.access_token,
                        tokenExpiry = expiresAt,
                    )
                } else {
                    println("[Auth] Token verify failed after successful exchange")
                    null
                }
            } else {
                println("[Auth] Token exchange FAILED: HTTP ${response.code}")
                println("[Auth] Response: $responseBody")
                null
            }
        }
    }

    // PKCE (RFC 7636): a fresh, high-entropy verifier per auth attempt, sent to the token
    // endpoint in place of a client secret; only its SHA-256 hash is exposed in the browser URL.
    internal fun generateCodeVerifier(): String {
        val bytes = ByteArray(32)
        java.security.SecureRandom().nextBytes(bytes)
        return java.util.Base64
            .getUrlEncoder()
            .withoutPadding()
            .encodeToString(bytes)
    }

    internal fun codeChallenge(verifier: String): String {
        val digest =
            java.security.MessageDigest
                .getInstance("SHA-256")
                .digest(verifier.toByteArray(Charsets.US_ASCII))
        return java.util.Base64
            .getUrlEncoder()
            .withoutPadding()
            .encodeToString(digest)
    }

    internal fun parseQueryString(query: String): Map<String, String> =
        query
            .split("&")
            .mapNotNull { pair ->
                val idx = pair.indexOf('=')
                if (idx > 0) {
                    java.net.URLDecoder.decode(pair.substring(0, idx), "UTF-8") to
                        java.net.URLDecoder.decode(pair.substring(idx + 1), "UTF-8")
                } else {
                    null
                }
            }.toMap()

    private fun sendResponse(
        exchange: com.sun.net.httpserver.HttpExchange,
        code: Int,
        body: String,
        headers: Map<String, String>,
    ) {
        val bytes = body.toByteArray()
        headers.forEach { (key, value) -> exchange.responseHeaders.add(key, value) }
        exchange.sendResponseHeaders(code, bytes.size.toLong())
        exchange.responseBody.write(bytes)
        exchange.close()
    }
}
