package com.descargo.app.work

import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.descargo.app.R
import com.descargo.app.data.AppSettings
import com.descargo.app.data.HistoryStore
import com.descargo.app.data.Subscription
import com.descargo.app.data.SubscriptionStore
import com.descargo.app.engine.Analysis
import com.descargo.app.engine.Engine
import com.descargo.app.engine.Presets
import java.util.concurrent.TimeUnit

/** Periodically lists each subscribed channel and queues uploads not seen before. */
class SubscriptionWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    companion object {
        private const val PERIODIC = "subscriptions"
        private const val NOW = "subscriptions-now"
        private const val CHECK_LIMIT = 15
        private const val SUBS_NOTIFICATION_ID = 0x5B5

        /** (Re)schedules the periodic check, or cancels it when there are no subscriptions. */
        fun schedule(ctx: Context, replace: Boolean = false) {
            val wm = WorkManager.getInstance(ctx)
            if (SubscriptionStore.items.value.none { it.enabled }) {
                wm.cancelUniqueWork(PERIODIC)
                return
            }
            val hours = AppSettings.current.subsIntervalH.toLong().coerceAtLeast(1)
            val req = PeriodicWorkRequestBuilder<SubscriptionWorker>(hours, TimeUnit.HOURS)
                .setConstraints(constraints())
                .setInitialDelay(15, TimeUnit.MINUTES)
                .build()
            wm.enqueueUniquePeriodicWork(
                PERIODIC,
                if (replace) ExistingPeriodicWorkPolicy.UPDATE else ExistingPeriodicWorkPolicy.KEEP,
                req,
            )
        }

        fun checkNow(ctx: Context) {
            val req = OneTimeWorkRequestBuilder<SubscriptionWorker>().setConstraints(constraints()).build()
            WorkManager.getInstance(ctx).enqueueUniqueWork(NOW, ExistingWorkPolicy.KEEP, req)
        }

        private fun constraints() = Constraints.Builder()
            .setRequiredNetworkType(if (AppSettings.current.wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
            .build()
    }

    override suspend fun doWork(): Result {
        val ctx = applicationContext
        AppSettings.init(ctx)
        HistoryStore.init(ctx)
        SubscriptionStore.init(ctx)
        try {
            Engine.awaitReady()
        } catch (_: Exception) {
            return Result.retry()
        }
        var queued = 0
        for (sub in SubscriptionStore.items.value.filter { it.enabled }) {
            queued += check(ctx, sub)
        }
        if (queued > 0) notifyNew(queued)
        return Result.success()
    }

    private fun check(ctx: Context, sub: Subscription): Int {
        return try {
            val a = Engine.analyze(ctx, sub.url, forcePlaylist = true, limit = CHECK_LIMIT)
            val entries = (a as? Analysis.Playlist)?.info?.entries.orEmpty()
            val fresh = entries.filter { it.url !in sub.seen }
            val preset = Presets.all.firstOrNull { it.label == sub.presetLabel } ?: Presets.all.first()
            DownloadQueue.enqueue(
                ctx,
                fresh.reversed().map { e -> // oldest first
                    DownloadQueue.Request(e.url, e.title, e.thumbnail, sub.site, preset.kind, preset.label, preset.args)
                },
            )
            SubscriptionStore.upsert(
                sub.copy(seen = sub.seen + entries.map { it.url }, lastCheck = System.currentTimeMillis(), lastError = null)
            )
            fresh.size
        } catch (e: Exception) {
            SubscriptionStore.upsert(sub.copy(lastCheck = System.currentTimeMillis(), lastError = Engine.friendlyError(e)))
            0
        }
    }

    private fun notifyNew(n: Int) {
        val nm = NotificationManagerCompat.from(applicationContext)
        val notif = NotificationCompat.Builder(applicationContext, DownloadWorker.CHANNEL_DONE)
            .setSmallIcon(R.drawable.ic_stat_download)
            .setContentTitle("Suscripciones")
            .setContentText(if (n == 1) "1 video nuevo en cola" else "$n videos nuevos en cola")
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .build()
        try {
            nm.notify(SUBS_NOTIFICATION_ID, notif)
        } catch (_: SecurityException) {
        }
    }
}
