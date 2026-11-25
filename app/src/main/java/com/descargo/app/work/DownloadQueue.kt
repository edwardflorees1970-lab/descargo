package com.descargo.app.work

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.descargo.app.data.AppSettings
import com.descargo.app.data.HistoryItem
import com.descargo.app.data.HistoryStore
import com.descargo.app.data.Settings
import com.descargo.app.data.Status
import com.descargo.app.engine.Kind
import com.yausername.youtubedl_android.YoutubeDL
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Downloads run in N WorkManager "lanes" (unique chains, APPEND_OR_REPLACE), which caps how many
 * run at once without parking idle workers. Workers always finish with success, so one failed or
 * cancelled item never breaks the rest of its lane; the real outcome lives in [HistoryStore].
 */
object DownloadQueue {
    const val TAG_ALL = "descargo"
    const val KEY_ID = "id"

    // Separator unlikely to appear in yt-dlp args.
    private const val SEP = '\u001F'

    data class Request(
        val url: String,
        val title: String,
        val thumbnail: String?,
        val site: String,
        val kind: Kind,
        val label: String,
        val args: List<Pair<String, String?>>,
    )

    /** historyId -> yt-dlp process id currently running for it. */
    internal val running = ConcurrentHashMap<String, String>()
    private val lane = AtomicInteger()

    fun encode(args: List<Pair<String, String?>>) = args.map { (k, v) -> if (v == null) k else "$k$SEP$v" }

    fun decode(args: List<String>): List<Pair<String, String?>> = args.map {
        val i = it.indexOf(SEP)
        if (i < 0) it to null else it.substring(0, i) to it.substring(i + 1)
    }

    fun enqueue(ctx: Context, requests: List<Request>) {
        if (requests.isEmpty()) return
        val s = AppSettings.current
        val items = requests.map { r ->
            HistoryItem(
                id = UUID.randomUUID().toString(),
                url = r.url, title = r.title, thumbnail = r.thumbnail, site = r.site,
                kind = r.kind, optionLabel = r.label,
                args = encode(r.args + extraArgs(r.kind, s, batch = requests.size > 1)),
            )
        }
        HistoryStore.add(items)
        schedule(ctx, items, s)
    }

    fun retry(ctx: Context, item: HistoryItem) {
        val fresh = item.copy(
            id = UUID.randomUUID().toString(), status = Status.QUEUED, error = null,
            createdAt = System.currentTimeMillis(),
        )
        HistoryStore.remove(listOf(item.id))
        HistoryStore.add(listOf(fresh))
        schedule(ctx, listOf(fresh), AppSettings.current)
    }

    /** Per-download scratch dir; kept while paused so yt-dlp can resume its partial files. */
    fun tmpDir(ctx: Context, id: String) = File(ctx.getExternalFilesDir(null) ?: ctx.filesDir, "tmp/$id")

    fun cancel(ctx: Context, id: String) {
        val wasPaused = HistoryStore.get(id)?.status == Status.PAUSED
        HistoryStore.update(id) { if (it.isPending) it.copy(status = Status.CANCELLED) else it }
        running[id]?.let { YoutubeDL.getInstance().destroyProcessById(it) }
        if (wasPaused) tmpDir(ctx, id).deleteRecursively()
    }

    fun cancelAll(ctx: Context) {
        HistoryStore.items.value.filter { it.isPending }.forEach { cancel(ctx, it.id) }
    }

    fun pause(id: String) {
        HistoryStore.update(id) { if (it.isActive) it.copy(status = Status.PAUSED) else it }
        running[id]?.let { YoutubeDL.getInstance().destroyProcessById(it) }
    }

    fun resume(ctx: Context, id: String) {
        val item = HistoryStore.get(id)?.takeIf { it.status == Status.PAUSED } ?: return
        HistoryStore.update(id) { it.copy(status = Status.QUEUED) }
        schedule(ctx, listOf(item), AppSettings.current)
    }

    fun pauseAll() = HistoryStore.items.value.filter { it.isActive }.forEach { pause(it.id) }

    fun resumeAll(ctx: Context) {
        val paused = HistoryStore.items.value.filter { it.status == Status.PAUSED }
        paused.forEach { p -> HistoryStore.update(p.id) { it.copy(status = Status.QUEUED) } }
        schedule(ctx, paused, AppSettings.current)
    }

    private fun extraArgs(kind: Kind, s: Settings, batch: Boolean): List<Pair<String, String?>> = buildList {
        if (batch) {
            // Spacing requests out keeps big playlists from tripping YouTube's anti-bot check.
            add("--sleep-requests" to "1")
            add("--sleep-interval" to "2")
            add("--max-sleep-interval" to "6")
        }
        if (kind == Kind.VIDEO && s.embedSubs) {
            add("--write-subs" to null)
            if (s.autoSubs) add("--write-auto-subs" to null)
            add("--sub-langs" to s.subLangs.ifBlank { "es.*,en.*" })
            add("--embed-subs" to null)
        }
    }

    private fun schedule(ctx: Context, items: List<HistoryItem>, s: Settings) {
        val wm = WorkManager.getInstance(ctx)
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(if (s.wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
            .build()
        for (item in items) {
            // Fresh work id each time (a resumed item is scheduled again); the history id travels as input.
            val work = OneTimeWorkRequestBuilder<DownloadWorker>()
                .setInputData(workDataOf(KEY_ID to item.id))
                .setConstraints(constraints)
                .addTag(TAG_ALL)
                .build()
            val name = "lane-" + (lane.getAndIncrement() % s.maxConcurrent)
            wm.enqueueUniqueWork(name, ExistingWorkPolicy.APPEND_OR_REPLACE, work)
        }
    }
}

/** "Cancelar" / "Pausar" buttons in the progress notification. */
class CancelReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getStringExtra(DownloadQueue.KEY_ID) ?: return
        HistoryStore.init(context)
        if (intent.getBooleanExtra(EXTRA_PAUSE, false)) DownloadQueue.pause(id) else DownloadQueue.cancel(context, id)
    }

    companion object {
        const val EXTRA_PAUSE = "pause"
    }
}
