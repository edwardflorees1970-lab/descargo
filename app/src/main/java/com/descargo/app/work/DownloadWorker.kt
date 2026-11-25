package com.descargo.app.work

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.descargo.app.R
import com.descargo.app.data.AppSettings
import com.descargo.app.data.HistoryStore
import com.descargo.app.data.Status
import com.descargo.app.engine.Engine
import com.descargo.app.engine.Kind
import com.descargo.app.engine.MusicInfo
import com.descargo.app.engine.Tagger
import com.descargo.app.engine.Tags
import com.yausername.youtubedl_android.YoutubeDL
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import java.io.File

class DownloadWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    companion object {
        const val KEY_HID = "hid"
        const val KEY_PROGRESS = "progress"
        const val KEY_ETA = "eta"
        const val KEY_LINE = "line"

        const val CHANNEL_PROGRESS = "downloads"
        const val CHANNEL_DONE = "downloads_done"

        private val SKIP_EXT = setOf("part", "ytdl", "jpg", "jpeg", "png", "webp", "json", "vtt", "srt", "ass")

        fun createChannels(ctx: Context) {
            val nm = ctx.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_PROGRESS, "Descargas en curso", NotificationManager.IMPORTANCE_LOW)
            )
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_DONE, "Descargas terminadas", NotificationManager.IMPORTANCE_DEFAULT)
            )
        }
    }

    private val hid = inputData.getString(DownloadQueue.KEY_ID) ?: id.toString()
    private val notificationId = hid.hashCode()
    private var title = "Descarga"

    override suspend fun getForegroundInfo(): ForegroundInfo = foregroundInfo(0f, "Preparando…")

    private fun cancelled() = HistoryStore.get(hid)?.status == Status.CANCELLED
    private fun paused() = HistoryStore.get(hid)?.status == Status.PAUSED
    private fun stopRequested() = cancelled() || paused()

    override suspend fun doWork(): Result {
        val ctx = applicationContext
        HistoryStore.init(ctx)
        AppSettings.init(ctx)
        val item = HistoryStore.get(hid) ?: return Result.success()
        if (item.status != Status.QUEUED && item.status != Status.RUNNING) return Result.success()
        title = item.title

        // Android 12+ may refuse a foreground service while the app is in background; keep going anyway.
        try {
            setForeground(foregroundInfo(0f, "Preparando…"))
        } catch (_: Exception) {
        }
        HistoryStore.update(hid) { it.copy(status = Status.RUNNING, error = null) }

        try {
            Engine.awaitReady()
        } catch (e: Exception) {
            return fail(Engine.friendlyError(e))
        }

        // Not wiped on start: a resumed download continues from the .part files left here.
        val tmp = DownloadQueue.tmpDir(ctx, hid)
        tmp.mkdirs()
        val args = DownloadQueue.decode(item.args) + Engine.runtimeArgs(item.kind, item.url, spoken = item.genre != null)
        val template = AppSettings.current.nameTemplate.pattern

        fun buildRequest() = Engine.baseRequest(ctx, item.url).apply {
            for ((k, v) in args) if (v == null) addOption(k) else addOption(k, v)
            addOption("-o", tmp.absolutePath + "/" + template)
            addOption("--no-mtime")
            addOption("--retries", "10")
            addOption("--fragment-retries", "10")
        }

        var processId = hid
        var lastUpdate = 0L
        try {
            coroutineScope {
                // If WorkManager stops us, kill the native yt-dlp process too.
                val killer = launch {
                    try {
                        awaitCancellation()
                    } finally {
                        YoutubeDL.getInstance().destroyProcessById(processId)
                    }
                }
                runInterruptible(Dispatchers.IO) {
                    Engine.withNetworkRetry(shouldStop = ::stopRequested) { attempt ->
                        processId = "$hid-${System.nanoTime()}-$attempt"
                        DownloadQueue.running[hid] = processId
                        if (stopRequested()) throw CancellationException("stopped")
                        YoutubeDL.getInstance().execute(buildRequest(), processId) { progress, eta, line ->
                            val now = System.currentTimeMillis()
                            if (now - lastUpdate > 500) {
                                lastUpdate = now
                                val status = describe(line)
                                setProgressAsync(
                                    workDataOf(
                                        KEY_HID to hid, KEY_PROGRESS to progress, KEY_ETA to eta, KEY_LINE to status,
                                    )
                                )
                                notify(foregroundInfo(progress, status))
                            }
                        }
                    }
                }
                killer.cancel()
            }
        } catch (e: Exception) {
            dismiss()
            if (paused()) return Result.success() // keep tmp for resume
            tmp.deleteRecursively()
            if (cancelled()) return Result.success()
            if (isStopped) return Result.retry()
            return fail(Engine.friendlyError(e))
        } finally {
            DownloadQueue.running.remove(hid)
        }

        setProgress(workDataOf(KEY_HID to hid, KEY_PROGRESS to 100f, KEY_LINE to "Guardando…"))
        val file = tmp.listFiles()
            ?.filter { it.isFile && it.extension.lowercase() !in SKIP_EXT }
            ?.maxByOrNull { it.length() }
        if (file == null) {
            tmp.deleteRecursively()
            return fail("No se generó ningún archivo.")
        }

        return try {
            val size = file.length()
            val saved = Storage.publish(ctx, file, item.kind, item.site)
            // Audiobook/podcast: chapter index + summary as a .txt next to the audio.
            item.notes?.let { Storage.publishText(ctx, saved.name.substringBeforeLast('.') + ".txt", it, item.kind, item.site) }
            if (AppSettings.current.autoTags && item.genre == null && (item.kind == Kind.MP3 || item.kind == Kind.FLAC)) {
                setProgress(workDataOf(KEY_HID to hid, KEY_PROGRESS to 100f, KEY_LINE to "Buscando datos y letra…"))
                enrichSong(saved.uri, saved.name, item.title, item.durationSec)
            }
            HistoryStore.update(hid) {
                it.copy(
                    status = Status.DONE, uri = saved.uri.toString(), mime = saved.mime,
                    fileName = saved.name, sizeBytes = size, error = null,
                )
            }
            dismiss()
            showDone(saved.uri, saved.mime, saved.name)
            Result.success()
        } catch (e: Exception) {
            fail("No se pudo guardar: ${e.message}")
        } finally {
            tmp.deleteRecursively()
        }
    }

    /** Completes album, year, cover and lyrics from MusicBrainz/LRCLIB. Never fails the download. */
    private fun enrichSong(uri: Uri, name: String, fallbackTitle: String, durationSec: Double?) {
        runCatching {
            val current = Tagger.read(applicationContext, uri)
            val (title, artist) = MusicInfo.clean(current.title.ifBlank { fallbackTitle }, current.artist)
            if (artist.isBlank()) return
            val match = MusicInfo.lookup(title, artist)
            val lyrics = MusicInfo.lyrics(match?.title ?: title, match?.artist ?: artist, durationSec?.toInt())
            if (match == null && lyrics == null) return
            val cover = match?.let { m ->
                File(applicationContext.cacheDir, "cover-$hid.jpg").takeIf { MusicInfo.cover(m, it) }
            }
            Tagger.write(
                applicationContext, uri, name,
                Tags(
                    title = match?.title ?: title,
                    artist = match?.artist ?: artist,
                    album = match?.album ?: current.album,
                    year = match?.year ?: current.year,
                ),
                cover = null, coverFile = cover, lyrics = lyrics,
            )
            cover?.delete()
        }
    }

    /** Records the failure but returns success so the rest of the lane keeps running. */
    private fun fail(message: String): Result {
        HistoryStore.update(hid) { it.copy(status = Status.FAILED, error = message) }
        dismiss()
        return Result.success()
    }

    private fun describe(line: String): String = when {
        line.contains("[Merger]") -> "Uniendo video y audio…"
        line.contains("[ExtractAudio]") -> "Convirtiendo audio…"
        line.contains("[EmbedSubtitle]") -> "Agregando subtítulos…"
        line.contains("[EmbedThumbnail]") || line.contains("[Metadata]") -> "Agregando portada y datos…"
        else -> "Descargando…"
    }

    private fun foregroundInfo(progress: Float, status: String): ForegroundInfo {
        val cancelIntent = Intent(applicationContext, CancelReceiver::class.java)
            .putExtra(DownloadQueue.KEY_ID, hid)
        val cancel = PendingIntent.getBroadcast(
            applicationContext, notificationId, cancelIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val pause = PendingIntent.getBroadcast(
            applicationContext, notificationId + 7,
            Intent(applicationContext, CancelReceiver::class.java)
                .putExtra(DownloadQueue.KEY_ID, hid).putExtra(CancelReceiver.EXTRA_PAUSE, true),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val n = NotificationCompat.Builder(applicationContext, CHANNEL_PROGRESS)
            .setSmallIcon(R.drawable.ic_stat_download)
            .setContentTitle(title)
            .setContentText(if (progress > 0) "$status ${progress.toInt()}%" else status)
            .setProgress(100, progress.toInt(), progress <= 0f)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(0, "Pausar", pause)
            .addAction(0, "Cancelar", cancel)
            .build()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(notificationId, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(notificationId, n)
        }
    }

    private fun notify(info: ForegroundInfo) {
        try {
            NotificationManagerCompat.from(applicationContext).notify(info.notificationId, info.notification)
        } catch (_: SecurityException) {
        }
    }

    private fun dismiss() = NotificationManagerCompat.from(applicationContext).cancel(notificationId)

    private fun showDone(uri: Uri, mime: String, name: String) {
        val open = Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        val pi = PendingIntent.getActivity(
            applicationContext, notificationId, open,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val n = NotificationCompat.Builder(applicationContext, CHANNEL_DONE)
            .setSmallIcon(R.drawable.ic_stat_download)
            .setContentTitle("Descarga completa")
            .setContentText(name)
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        try {
            NotificationManagerCompat.from(applicationContext).notify(notificationId + 1, n)
        } catch (_: SecurityException) {
        }
    }
}
