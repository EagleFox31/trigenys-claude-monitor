package com.ghayyath.claudepulse

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Periodically fetch each account independently; preserve cached snapshots on error. */
class RefreshWorker(private val ctx: Context, params: WorkerParameters) :
    CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        try {
            AccountVault.ensureDefaults(ctx)
            AccountVault.read(ctx).filter { it.connected }.forEach { account ->
                val result = ApiClient.fetchUsage(ctx, account.id)
                if (result.error == null) {
                    UsageCache.save(ctx, account.id, result)
                } else {
                    UsageCache.setError(ctx, account.id, result.error)
                }
            }
            PulseWidget.updateAll(ctx)
            Result.success()
        } catch (_: Exception) {
            // Keystore unavailable / device locked / temporary provider failure.
            // Do not delete credentials or replace valid snapshots with zeroes.
            PulseWidget.updateAll(ctx)
            Result.retry()
        }
    }
}
