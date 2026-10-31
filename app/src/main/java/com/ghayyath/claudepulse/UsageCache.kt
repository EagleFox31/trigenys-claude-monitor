package com.ghayyath.claudepulse

import android.content.Context
import org.json.JSONObject

/** No credentials in this cache. A failed fetch never erases the last successful snapshot. */
object UsageCache {
    private fun prefs(ctx: Context) =
        ctx.applicationContext.getSharedPreferences("trigenys_usage_snapshots", Context.MODE_PRIVATE)

    fun save(ctx: Context, id: String, value: UsageData) {
        require(value.error == null)
        val json = JSONObject().apply {
            put("limits", UsageData.limitsToJson(value.limits))
            put("plan", value.planLabel)
            put("cached_at", value.cachedAt)
        }
        prefs(ctx).edit().putString("snapshot_" + id, json.toString())
            .remove("error_" + id).apply()
    }

    fun get(ctx: Context, id: String): UsageData? {
        val raw = prefs(ctx).getString("snapshot_" + id, null) ?: return null
        return try {
            val json = JSONObject(raw)
            UsageData(
                limits = UsageData.limitsFromJson(json.getString("limits")),
                planLabel = json.optString("plan"),
                cachedAt = json.optString("cached_at").takeIf { it.isNotBlank() }
            )
        } catch (_: Exception) {
            null
        }
    }

    fun setError(ctx: Context, id: String, reason: String) {
        prefs(ctx).edit().putString("error_" + id, reason).apply()
    }

    fun error(ctx: Context, id: String): String? = prefs(ctx).getString("error_" + id, null)

    fun clear(ctx: Context, id: String) {
        prefs(ctx).edit().remove("snapshot_" + id).remove("error_" + id).apply()
    }
}
