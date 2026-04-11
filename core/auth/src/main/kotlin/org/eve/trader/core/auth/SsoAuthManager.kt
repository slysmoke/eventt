package org.eve.trader.core.auth

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.eve.trader.core.database.CharacterDao
import org.eve.trader.core.http.EveHttpClient
import org.eve.trader.core.model.CharacterModel
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.awt.Desktop
import java.net.URI
import java.net.InetSocketAddress
import com.sun.net.httpserver.HttpServer

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

    private const val CLIENT_ID = "9bacf8234c4b41888f00b084413868c0"
    private const val CLIENT_SECRET = "eat_1NcbJbALfafoObI921w8HMpbUPKjEosp9_4NSjgX"
    private const val CALLBACK_URL = "http://localhost:8000/callback"
    private const val SCOPES = "esi-calendar.respond_calendar_events.v1 esi-calendar.read_calendar_events.v1 esi-location.read_location.v1 esi-location.read_ship_type.v1 esi-mail.organize_mail.v1 esi-mail.read_mail.v1 esi-mail.send_mail.v1 esi-skills.read_skills.v1 esi-skills.read_skillqueue.v1 esi-wallet.read_character_wallet.v1 esi-wallet.read_corporation_wallet.v1 esi-search.search_structures.v1 esi-clones.read_clones.v1 esi-characters.read_contacts.v1 esi-universe.read_structures.v1 esi-killmails.read_killmails.v1 esi-corporations.read_corporation_membership.v1 esi-assets.read_assets.v1 esi-planets.manage_planets.v1 esi-fleets.read_fleet.v1 esi-fleets.write_fleet.v1 esi-ui.open_window.v1 esi-ui.write_waypoint.v1 esi-characters.write_contacts.v1 esi-markets.structure_markets.v1 esi-corporations.read_structures.v1 esi-characters.read_loyalty.v1 esi-characters.read_chat_channels.v1 esi-characters.read_medals.v1 esi-characters.read_standings.v1 esi-characters.read_agents_research.v1 esi-industry.read_character_jobs.v1 esi-markets.read_character_orders.v1 esi-characters.read_blueprints.v1 esi-characters.read_corporation_roles.v1 esi-location.read_online.v1 esi-contracts.read_character_contracts.v1 esi-clones.read_implants.v1 esi-characters.read_fatigue.v1 esi-killmails.read_corporation_killmails.v1 esi-corporations.track_members.v1 esi-wallet.read_corporation_wallets.v1 esi-characters.read_notifications.v1 esi-corporations.read_divisions.v1 esi-corporations.read_contacts.v1 esi-assets.read_corporation_assets.v1 esi-corporations.read_titles.v1 esi-corporations.read_blueprints.v1 esi-contracts.read_corporation_contracts.v1 esi-corporations.read_standings.v1 esi-corporations.read_starbases.v1 esi-industry.read_corporation_jobs.v1 esi-markets.read_corporation_orders.v1 esi-corporations.read_container_logs.v1 esi-industry.read_character_mining.v1 esi-industry.read_corporation_mining.v1 esi-planets.read_customs_offices.v1 esi-corporations.read_facilities.v1 esi-corporations.read_medals.v1 esi-characters.read_titles.v1 esi-alliances.read_contacts.v1 esi-characters.read_fw_stats.v1 esi-corporations.read_fw_stats.v1 esi-corporations.read_projects.v1 esi-corporations.read_freelance_jobs.v1 esi-characters.read_freelance_jobs.v1 publicData esi-fittings.read_fittings.v1 esi-fittings.write_fittings.v1"

    private var server: HttpServer? = null
    @Volatile
    private var authResult: AuthResult? = null
    private val lock = Object()

    data class AuthResult(
        val success: Boolean,
        val character: CharacterModel? = null,
        val error: String? = null,
    )

    fun startAuth(): AuthResult {
        val state = java.util.UUID.randomUUID().toString()
        authResult = null

        val encodedScopes = SCOPES.replace(" ", "+")
        val encodedRedirect = java.net.URLEncoder.encode(CALLBACK_URL, "UTF-8")
        val authUrl = "$ESI_SSO_URL/authorize?response_type=code&redirect_uri=$encodedRedirect&client_id=$CLIENT_ID&scope=$encodedScopes&state=$state"

        startCallbackServer()

        try {
            if (Desktop.isDesktopSupported()) {
                Desktop.getDesktop().browse(URI(authUrl))
            } else {
                println("Open this URL in your browser: $authUrl")
            }
        } catch (e: Exception) {
            println("Could not open browser. Open this URL manually: $authUrl")
        }

        // Wait for callback (max 5 minutes)
        var waited = 0L
        while (authResult == null && waited < 300_000) {
            Thread.sleep(500)
            waited += 500
        }

        stopServer()
        return authResult ?: AuthResult(success = false, error = "Auth timed out")
    }

    fun refreshToken(refreshToken: String): TokenResponse? {
        val client = EveHttpClient.getClient()

        val body = "grant_type=refresh_token&refresh_token=$refreshToken"
        val requestBody = body.toRequestBody("application/x-www-form-urlencoded".toMediaType())

        val request = Request.Builder()
            .url("$ESI_SSO_URL/token")
            .post(requestBody)
            .header("Authorization", "Basic " + java.util.Base64.getEncoder().encodeToString("$CLIENT_ID:$CLIENT_SECRET".toByteArray()))
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

        val request = Request.Builder()
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

        val refreshToken = runCatching {
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
        val srv = HttpServer.create(InetSocketAddress("127.0.0.1", 8000), 0)
        server = srv

        srv.createContext("/callback") { exchange ->
            val query = exchange.requestURI.query ?: ""
            val params = parseQueryString(query)

            val error = params["error"]
            val code = params["code"]

            val responseHeaders = mapOf("Content-Type" to "text/html; charset=utf-8")

            if (error != null) {
                val html = "<html><body><h1>Authentication failed</h1><p>$error</p><p>You can close this window.</p></body></html>"
                sendResponse(exchange, 200, html, responseHeaders)
                synchronized(lock) {
                    authResult = AuthResult(success = false, error = "SSO error: $error")
                }
                return@createContext
            }

            if (code != null) {
                val character = exchangeCodeForToken(code)
                if (character != null) {
                    CharacterDao.insert(character)
                    val html = "<html><body><h1>Authentication successful!</h1><p>You can close this window and return to EVE Trader.</p></body></html>"
                    sendResponse(exchange, 200, html, responseHeaders)
                    synchronized(lock) {
                        authResult = AuthResult(success = true, character = character)
                    }
                } else {
                    val html = "<html><body><h1>Authentication failed</h1><p>Could not exchange token. The code may have been used already.</p><p>You can close this window.</p></body></html>"
                    sendResponse(exchange, 200, html, responseHeaders)
                    synchronized(lock) {
                        authResult = AuthResult(success = false, error = "Token exchange failed — code may be invalid or expired")
                    }
                }
            } else {
                val html = "<html><body><h1>Authentication failed</h1><p>No authorization code received.</p><p>You can close this window.</p></body></html>"
                sendResponse(exchange, 200, html, responseHeaders)
                synchronized(lock) {
                    authResult = AuthResult(success = false, error = "No authorization code")
                }
            }
        }

        srv.setExecutor(java.util.concurrent.Executors.newSingleThreadExecutor())
        srv.start()
    }

    private fun stopServer() {
        server?.stop(1)
        server = null
    }

    private fun exchangeCodeForToken(code: String): CharacterModel? {
        val client = EveHttpClient.getClient()

        val encodedRedirect = java.net.URLEncoder.encode(CALLBACK_URL, "UTF-8")
        val body = "grant_type=authorization_code&code=$code&redirect_uri=$encodedRedirect"
        val requestBody = body.toRequestBody("application/x-www-form-urlencoded".toMediaType())

        val request = Request.Builder()
            .url("$ESI_SSO_URL/token")
            .post(requestBody)
            .header("Authorization", "Basic " + java.util.Base64.getEncoder().encodeToString("$CLIENT_ID:$CLIENT_SECRET".toByteArray()))
            .header("Host", "login.eveonline.com")
            .build()

        return client.newCall(request).execute().use { response ->
            if (response.isSuccessful) {
                val json = Json { ignoreUnknownKeys = true }
                val tokenResponse = json.decodeFromString<TokenResponse>(response.body?.string() ?: "")

                val charInfo = verifyToken(tokenResponse.access_token)
                if (charInfo != null) {
                    val expiresAt = System.currentTimeMillis() + (tokenResponse.expires_in * 1000L)
                    CharacterModel(
                        id = charInfo.CharacterID,
                        name = charInfo.CharacterName,
                        refreshToken = tokenResponse.refresh_token,
                        accessToken = tokenResponse.access_token,
                        tokenExpiry = expiresAt,
                    )
                } else {
                    println("[Auth] Could not verify token — character info unavailable")
                    null
                }
            } else {
                val errorBody = response.body?.string() ?: ""
                println("[Auth] Token exchange failed: ${response.code}")
                null
            }
        }
    }

    private fun parseQueryString(query: String): Map<String, String> {
        return query.split("&").mapNotNull { pair ->
            val parts = pair.split("=")
            if (parts.size == 2) {
                java.net.URLDecoder.decode(parts[0], "UTF-8") to java.net.URLDecoder.decode(parts[1], "UTF-8")
            } else null
        }.toMap()
    }

    private fun sendResponse(exchange: com.sun.net.httpserver.HttpExchange, code: Int, body: String, headers: Map<String, String>) {
        val bytes = body.toByteArray()
        exchange.sendResponseHeaders(code, bytes.size.toLong())
        headers.forEach { (key, value) -> exchange.responseHeaders.add(key, value) }
        exchange.responseBody.write(bytes)
        exchange.close()
    }
}
