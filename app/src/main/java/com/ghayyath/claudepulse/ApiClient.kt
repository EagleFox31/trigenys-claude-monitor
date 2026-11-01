package com.ghayyath.claudepulse

import android.content.Context
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant

/** Direct, per-account usage fetch. Anthropic OAuth usage endpoints are undocumented. */
object ApiClient {
    private const val USAGE_URL = "https://api.anthropic.com/api/oauth/usage"

    fun fetchUsage(ctx: Context, accountId: String): UsageData {
        val auth = TokenManager.getAccessToken(ctx, accountId)
        if (auth.token == null) {
            return UsageData.placeholder().copy(error = auth.error ?: "auth_required")
        }
        val first = query(auth.token)
        if (first.error != "auth_required") return first
        val renewed = TokenManager.refreshAccessToken(ctx, accountId, force = true)
        if (renewed.token == null) {
            return UsageData.placeholder().copy(error = renewed.error ?: "auth_required")
        }
        return query(renewed.token)
    }

    private fun query(token: String): UsageData {
        val conn = URL(USAGE_URL).openConnection() as HttpURLConnection
        return try {
            conn.requestMethod = "GET"
            conn.setRequestProperty("Authorization", "Bearer " + token)
            conn.setRequestProperty("anthropic-beta", "oauth-2025-04-20")
            conn.setRequestProperty("Accept", "application/json")
            conn.connectTimeout = 10000
            conn.readTimeout = 10000
            when (conn.responseCode) {
                200 -> UsageData.fromJson(
                    JSONObject(conn.inputStream.bufferedReader().readText())
                ).copy(cachedAt = Instant.now().toString(), error = null)
                401, 403 -> UsageData.placeholder().copy(error = "auth_required")
                429 -> UsageData.placeholder().copy(error = "rate_limited")
                else -> UsageData.placeholder().copy(error = "http_" + conn.responseCode)
            }
        } catch (_: Exception) {
            UsageData.placeholder().copy(error = "offline_or_unavailable")
        } finally {
            conn.disconnect()
        }
    }
}
