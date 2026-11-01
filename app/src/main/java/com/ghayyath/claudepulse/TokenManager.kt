package com.ghayyath.claudepulse

import android.content.Context
import android.net.Uri
import android.util.Base64
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.security.SecureRandom

/** Isolated OAuth PKCE flow and refresh token rotation for each Claude account. */
object TokenManager {
    data class TokenResult(val token: String? = null, val error: String? = null)
    private const val TOKEN_URL = "https://platform.claude.com/v1/oauth/token"
    private const val AUTH_URL = "https://claude.com/cai/oauth/authorize"
    private const val REDIRECT_URL = "https://platform.claude.com/oauth/code/callback"
    private const val CLIENT_ID = "9d1c250a-e61b-44d9-88ed-5944d1962f5e"
    private const val SCOPES = "user:profile user:inference"

    fun hasCredentials(ctx: Context, id: String) = AccountVault.find(ctx, id)?.connected == true

    fun disconnect(ctx: Context, id: String) = AccountVault.update(ctx, id) {
        it.copy(accessToken = null, refreshToken = null, expiresAtMs = 0,
            pendingVerifier = null, pendingState = null)
    }

    fun buildLoginUrl(ctx: Context, id: String): String {
        val verifier = randomString()
        val state = randomString()
        val challenge = Base64.encodeToString(
            MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)),
            Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP
        )
        AccountVault.update(ctx, id) { it.copy(pendingVerifier = verifier, pendingState = state) }
        return Uri.parse(AUTH_URL).buildUpon()
            .appendQueryParameter("code", "true")
            .appendQueryParameter("client_id", CLIENT_ID)
            .appendQueryParameter("response_type", "code")
            .appendQueryParameter("redirect_uri", REDIRECT_URL)
            .appendQueryParameter("scope", SCOPES)
            .appendQueryParameter("code_challenge", challenge)
            .appendQueryParameter("code_challenge_method", "S256")
            .appendQueryParameter("state", state)
            .build().toString()
    }

    fun completeLogin(ctx: Context, id: String, raw: String): String? {
        val account = AccountVault.find(ctx, id) ?: return "unknown_account"
        val verifier = account.pendingVerifier ?: return "start_login_first"
        val expected = account.pendingState ?: return "start_login_first"
        val pieces = raw.trim().split("#", limit = 2)
        if (pieces.size != 2 || pieces[0].isBlank() || pieces[1] != expected) return "state_mismatch"
        val conn = URL(TOKEN_URL).openConnection() as HttpURLConnection
        return try {
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json")
            conn.doOutput = true
            conn.connectTimeout = 15000
            conn.readTimeout = 15000
            conn.outputStream.bufferedWriter().use {
                it.write(JSONObject().apply {
                    put("grant_type", "authorization_code")
                    put("code", pieces[0])
                    put("redirect_uri", REDIRECT_URL)
                    put("client_id", CLIENT_ID)
                    put("code_verifier", verifier)
                    put("state", expected)
                }.toString())
            }
            if (conn.responseCode != 200) return statusError(conn.responseCode)
            val data = JSONObject(conn.inputStream.bufferedReader().readText())
            val token = data.getString("access_token")
            val refresh = data.optString("refresh_token").takeIf { it.isNotBlank() }
            val expiry = System.currentTimeMillis() + data.optLong("expires_in", 28800) * 1000L
            AccountVault.update(ctx, id) {
                it.copy(accessToken = token, refreshToken = refresh, expiresAtMs = expiry,
                    pendingVerifier = null, pendingState = null)
            }
            null
        } catch (_: Exception) {
            "login_or_network_error"
        } finally {
            conn.disconnect()
        }
    }

    fun getAccessToken(ctx: Context, id: String): TokenResult {
        val account = AccountVault.find(ctx, id) ?: return TokenResult(error = "unknown_account")
        if (account.accessToken != null &&
            System.currentTimeMillis() < account.expiresAtMs - 300000L) {
            return TokenResult(token = account.accessToken)
        }
        return refreshAccessToken(ctx, id)
    }

    @Synchronized fun refreshAccessToken(ctx: Context, id: String, force: Boolean = false): TokenResult {
        val account = AccountVault.find(ctx, id) ?: return TokenResult(error = "unknown_account")
        if (!force && account.accessToken != null &&
            System.currentTimeMillis() < account.expiresAtMs - 300000L) {
            return TokenResult(token = account.accessToken)
        }
        val refresh = account.refreshToken ?: return TokenResult(error = "auth_required")
        val conn = URL(TOKEN_URL).openConnection() as HttpURLConnection
        return try {
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json")
            conn.doOutput = true
            conn.connectTimeout = 15000
            conn.readTimeout = 15000
            conn.outputStream.bufferedWriter().use {
                it.write(JSONObject().apply {
                    put("grant_type", "refresh_token")
                    put("refresh_token", refresh)
                    put("client_id", CLIENT_ID)
                }.toString())
            }
            if (conn.responseCode != 200) return TokenResult(error = statusError(conn.responseCode))
            val data = JSONObject(conn.inputStream.bufferedReader().readText())
            val access = data.getString("access_token")
            val rotated = data.optString("refresh_token").takeIf { it.isNotBlank() } ?: refresh
            val expiry = System.currentTimeMillis() + data.optLong("expires_in", 28800) * 1000L
            AccountVault.update(ctx, id) {
                it.copy(accessToken = access, refreshToken = rotated, expiresAtMs = expiry)
            }
            TokenResult(token = access)
        } catch (_: Exception) {
            TokenResult(error = "refresh_or_network_error")
        } finally {
            conn.disconnect()
        }
    }

    private fun randomString(): String =
        Base64.encodeToString(ByteArray(32).also { SecureRandom().nextBytes(it) },
            Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)

    private fun statusError(code: Int) = when (code) {
        400, 401, 403 -> "auth_required"
        429 -> "rate_limited"
        else -> "http_" + code
    }
}
