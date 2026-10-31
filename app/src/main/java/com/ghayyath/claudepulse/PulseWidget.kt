package com.ghayyath.claudepulse

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

/** Four accounts on a single resizable native widget. No KWGT and no external server. */
class PulseWidget : AppWidgetProvider() {
    override fun onUpdate(ctx: Context, manager: AppWidgetManager, ids: IntArray) {
        super.onUpdate(ctx, manager, ids)
        scheduleRefresh(ctx)
        render(ctx, manager, ids)
    }

    override fun onEnabled(ctx: Context) {
        super.onEnabled(ctx)
        scheduleRefresh(ctx)
        requestRefresh(ctx)
    }

    override fun onReceive(ctx: Context, intent: Intent) {
        super.onReceive(ctx, intent)
        if (intent.action == ACTION_REFRESH) requestRefresh(ctx)
    }

    companion object {
        private const val ACTION_REFRESH = "com.trigenys.claudemonitor.REFRESH"

        private data class RowIds(
            val name: Int, val usage: Int, val reset: Int,
            val sessionBar: Int, val weeklyBar: Int
        )

        private val rows = listOf(
            RowIds(R.id.account_name_1, R.id.account_usage_1, R.id.account_reset_1,
                R.id.session_bar_1, R.id.weekly_bar_1),
            RowIds(R.id.account_name_2, R.id.account_usage_2, R.id.account_reset_2,
                R.id.session_bar_2, R.id.weekly_bar_2),
            RowIds(R.id.account_name_3, R.id.account_usage_3, R.id.account_reset_3,
                R.id.session_bar_3, R.id.weekly_bar_3),
            RowIds(R.id.account_name_4, R.id.account_usage_4, R.id.account_reset_4,
                R.id.session_bar_4, R.id.weekly_bar_4)
        )

        fun scheduleRefresh(ctx: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED).build()
            val request = PeriodicWorkRequestBuilder<RefreshWorker>(15, TimeUnit.MINUTES)
                .setConstraints(constraints)
                .build()
            WorkManager.getInstance(ctx).enqueueUniquePeriodicWork(
                "trigenys_periodic_usage", ExistingPeriodicWorkPolicy.KEEP, request
            )
        }

        fun requestRefresh(ctx: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED).build()
            WorkManager.getInstance(ctx).enqueueUniqueWork(
                "trigenys_manual_usage", ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<RefreshWorker>().setConstraints(constraints).build()
            )
        }

        fun updateAll(ctx: Context) {
            val manager = AppWidgetManager.getInstance(ctx)
            val ids = manager.getAppWidgetIds(ComponentName(ctx, PulseWidget::class.java))
            if (ids.isNotEmpty()) render(ctx, manager, ids)
        }

        private fun render(ctx: Context, manager: AppWidgetManager, widgetIds: IntArray) {
            val accounts = try {
                AccountVault.ensureDefaults(ctx)
                AccountVault.read(ctx)
            } catch (_: Exception) {
                emptyList()
            }
            widgetIds.forEach { widgetId ->
                val views = RemoteViews(ctx.packageName, R.layout.widget_accounts)
                val refreshIntent = Intent(ctx, PulseWidget::class.java).apply {
                    action = ACTION_REFRESH
                }
                val refreshAction = PendingIntent.getBroadcast(
                    ctx, 0, refreshIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                views.setOnClickPendingIntent(R.id.refresh_button, refreshAction)
                val openIntent = Intent(ctx, SetupActivity::class.java)
                val openAction = PendingIntent.getActivity(
                    ctx, 0, openIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                views.setOnClickPendingIntent(R.id.widget_title, openAction)

                rows.forEachIndexed { index, ids ->
                    val account = accounts.getOrNull(index)
                    val snapshot = account?.let { UsageCache.get(ctx, it.id) }
                    val error = account?.let { UsageCache.error(ctx, it.id) }
                    val session = snapshot?.limits?.firstOrNull { it.label == "Session" }
                    val weekly = snapshot?.limits?.firstOrNull { it.label == "Weekly" }
                    views.setTextViewText(ids.name, account?.label ?: "Compte " + (index + 1))
                    val sessionText = session?.percent?.toString()?.plus("%") ?: "—"
                    val weeklyText = weekly?.percent?.toString()?.plus("%") ?: "—"
                    views.setTextViewText(ids.usage, "5 h  " + sessionText + "   •   7 j  " + weeklyText)
                    views.setProgressBar(ids.sessionBar, 100, session?.percent?.coerceIn(0, 100) ?: 0, false)
                    views.setProgressBar(ids.weeklyBar, 100, weekly?.percent?.coerceIn(0, 100) ?: 0, false)
                    val detail = when {
                        account == null -> "Coffre non accessible"
                        !account.connected -> "Non connecté"
                        snapshot == null -> "En attente du premier relevé"
                        else -> "↻ 5h " + TimeLabels.untilReset(session?.resetsAt) +
                            "  /  7j " + TimeLabels.untilReset(weekly?.resetsAt) +
                            "  •  " + TimeLabels.age(snapshot.cachedAt)
                    }
                    val suffix = if (error != null) "  ⚠ " + error else ""
                    views.setTextViewText(ids.reset, detail + suffix)
                }
                manager.updateAppWidget(widgetId, views)
            }
        }
    }
}
