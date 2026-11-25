package com.descargo.app.ui

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.descargo.app.DescargoApp
import com.descargo.app.data.AppSettings
import com.descargo.app.data.Cookies
import com.descargo.app.data.HistoryItem
import com.descargo.app.data.HistoryStore
import com.descargo.app.data.Settings
import com.descargo.app.engine.Analysis
import com.descargo.app.engine.DownloadOption
import com.descargo.app.engine.Engine
import com.descargo.app.engine.Kind
import com.descargo.app.engine.MediaInfo
import com.descargo.app.engine.PlaylistInfo
import com.descargo.app.engine.Presets
import com.descargo.app.data.Subscription
import com.descargo.app.data.SubscriptionStore
import com.descargo.app.engine.Tagger
import com.descargo.app.engine.Tags
import com.descargo.app.work.SubscriptionWorker
import com.descargo.app.work.DownloadQueue
import com.descargo.app.work.DownloadWorker
import com.descargo.app.work.Storage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class Screen(val label: String) { HOME("Inicio"), BROWSER("Navegador"), LIBRARY("Biblioteca"), SETTINGS("Ajustes") }

sealed interface UiState {
    data object Idle : UiState
    data object Loading : UiState
    data class Error(val message: String) : UiState
    data class Loaded(val info: MediaInfo, val inPlaylist: Boolean) : UiState
    data class PlaylistLoaded(val info: PlaylistInfo) : UiState
}

data class Progress(val percent: Float, val line: String, val eta: Long)

class MainViewModel(app: Application) : AndroidViewModel(app) {
    var screen by mutableStateOf(Screen.HOME)

    // Home
    var url by mutableStateOf("")
    var state by mutableStateOf<UiState>(UiState.Idle)
        private set
    var tab by mutableStateOf(Kind.VIDEO)
    var selected by mutableStateOf<DownloadOption?>(null)
    var message by mutableStateOf<String?>(null)
    var clipboardLink by mutableStateOf<String?>(null)
    private var lastClipboard: String? = null

    // Clip (download only a fragment)
    var clipOn by mutableStateOf(false)
    var clipStart by mutableStateOf("0:00")
    var clipEnd by mutableStateOf("")

    // Playlist
    var checked by mutableStateOf<Set<Int>>(emptySet())
    var preset by mutableStateOf(Presets.all.first())
    val subscriptions = SubscriptionStore.items

    // Browser
    var browserUrl by mutableStateOf("https://m.youtube.com")
    var browserCanGoBack by mutableStateOf(false)
    var browserProgress by mutableStateOf(100)

    val engineState = Engine.state
    val settings = AppSettings.state
    val history = HistoryStore.items

    val progress: Flow<Map<String, Progress>> =
        WorkManager.getInstance(app).getWorkInfosByTagFlow(DownloadQueue.TAG_ALL).map { list ->
            list.filter { it.state == WorkInfo.State.RUNNING }.associate { w ->
                (w.progress.getString(DownloadWorker.KEY_HID) ?: w.id.toString()) to Progress(
                    w.progress.getFloat(DownloadWorker.KEY_PROGRESS, 0f),
                    w.progress.getString(DownloadWorker.KEY_LINE) ?: "Descargando…",
                    w.progress.getLong(DownloadWorker.KEY_ETA, -1),
                )
            }
        }

    private var job: Job? = null

    fun analyze(forcePlaylist: Boolean = false) {
        val link = extractUrl(url) ?: run {
            state = UiState.Error("Pega un enlace válido (https://…)")
            return
        }
        url = link
        if (clipboardLink == link) clipboardLink = null
        screen = Screen.HOME
        job?.cancel()
        job = viewModelScope.launch {
            state = UiState.Loading
            state = try {
                Engine.awaitReady()
                when (val a = withContext(Dispatchers.IO) { Engine.analyze(getApplication(), link, forcePlaylist) }) {
                    is Analysis.Single -> {
                        tab = AppSettings.current.openTab
                        selected = defaultOption(a.info, tab)
                        clipOn = false
                        clipStart = "0:00"
                        clipEnd = a.info.durationSec?.let { formatDuration(it) } ?: ""
                        UiState.Loaded(a.info, a.inPlaylist)
                    }
                    is Analysis.Playlist -> {
                        checked = a.info.entries.indices.toSet()
                        preset = Presets.default(AppSettings.current.videoHeight)
                        UiState.PlaylistLoaded(a.info)
                    }
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                UiState.Error(Engine.friendlyError(e))
            }
        }
    }

    fun analyzeLink(link: String) {
        url = link
        analyze()
    }

    private fun defaultOption(info: MediaInfo, k: Kind): DownloadOption? {
        val list = info.options[k].orEmpty()
        val s = AppSettings.current
        return when (k) {
            Kind.VIDEO -> if (s.videoHeight == 0) list.firstOrNull()
            else list.filter { it.rank != null && it.rank <= s.videoHeight }.maxByOrNull { it.rank!! }
                ?: list.lastOrNull()
            Kind.MP3 -> list.firstOrNull { it.rank == s.mp3Kbps } ?: list.firstOrNull()
            else -> list.firstOrNull()
        }
    }

    fun selectTab(k: Kind) {
        tab = k
        val info = (state as? UiState.Loaded)?.info ?: return
        selected = defaultOption(info, k)
    }

    fun download() {
        val info = (state as? UiState.Loaded)?.info ?: return
        val opt = selected ?: return
        var args = opt.args
        var label = opt.label.trim() + " · " + opt.kind.title
        if (clipOn) {
            val s = Engine.parseTime(clipStart)
            val e = Engine.parseTime(clipEnd)
            val dur = info.durationSec?.toInt()
            if (s == null || e == null || e <= s || (dur != null && s >= dur)) {
                message = "Recorte inválido: usa minutos:segundos, y el fin debe ser mayor que el inicio"
                return
            }
            args = args + Engine.clipArgs(s, e)
            label += " · ✂ ${formatDuration(s.toDouble())}–${formatDuration(e.toDouble())}"
        }
        DownloadQueue.enqueue(
            getApplication(),
            listOf(DownloadQueue.Request(info.url, info.title, info.thumbnail, info.site, opt.kind, label, args)),
        )
        message = "Descarga agregada: ${opt.label.trim()}"
    }

    fun toggle(i: Int) {
        checked = if (i in checked) checked - i else checked + i
    }

    fun checkAll(all: Boolean) {
        val pl = (state as? UiState.PlaylistLoaded)?.info ?: return
        checked = if (all) pl.entries.indices.toSet() else emptySet()
    }

    fun downloadPlaylist() {
        val pl = (state as? UiState.PlaylistLoaded)?.info ?: return
        val p = preset
        val reqs = pl.entries.filterIndexed { i, _ -> i in checked }.map { e ->
            DownloadQueue.Request(e.url, e.title, e.thumbnail, pl.site, p.kind, p.label, p.args)
        }
        DownloadQueue.enqueue(getApplication(), reqs)
        message = "${reqs.size} descargas en cola (${AppSettings.current.maxConcurrent} a la vez)"
    }

    fun onClipboard(text: CharSequence?) {
        if (!AppSettings.current.watchClipboard) return
        val link = extractUrl(text?.toString()) ?: return
        if (link == lastClipboard || link == url) return
        lastClipboard = link
        clipboardLink = link
    }

    fun cancel(id: String) = DownloadQueue.cancel(getApplication(), id)
    fun cancelAll() = DownloadQueue.cancelAll(getApplication())
    fun pause(id: String) = DownloadQueue.pause(id)
    fun resume(id: String) = DownloadQueue.resume(getApplication(), id)
    fun pauseAll() = DownloadQueue.pauseAll()
    fun resumeAll() = DownloadQueue.resumeAll(getApplication())

    // Subscriptions
    fun subscribe() {
        val pl = (state as? UiState.PlaylistLoaded)?.info ?: return
        SubscriptionStore.upsert(
            Subscription(
                url = pl.url, title = pl.title, site = pl.site, presetLabel = preset.label,
                // Existing uploads count as seen: only future ones are downloaded.
                seen = pl.entries.map { it.url }.toSet(), lastCheck = System.currentTimeMillis(),
            )
        )
        SubscriptionWorker.schedule(getApplication(), replace = true)
        message = "Suscrito: los videos nuevos se bajarán como «${preset.label}»"
    }

    fun unsubscribe(id: String) {
        SubscriptionStore.remove(id)
        SubscriptionWorker.schedule(getApplication(), replace = true)
    }

    fun checkSubscriptionsNow() {
        SubscriptionWorker.checkNow(getApplication())
        message = "Revisando suscripciones…"
    }

    // Download folder (user-picked, with Audio/ and Video/ inside)
    /** Earlier downloads that could be moved into the chosen folder; >0 shows the offer dialog. */
    var pendingMove by mutableStateOf(0)
    var moving by mutableStateOf(false)
        private set

    private val treeFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION

    fun onFolderPicked(uri: Uri?) {
        if (uri == null) return
        val app = getApplication<Application>()
        val cr = app.contentResolver
        try {
            cr.takePersistableUriPermission(uri, treeFlags)
        } catch (_: SecurityException) {
            message = "Android no dio permiso para esa carpeta. Prueba otra."
            return
        }
        val old = AppSettings.current.treeUri
        if (old.isNotBlank() && old != uri.toString()) runCatching { cr.releasePersistableUriPermission(Uri.parse(old), treeFlags) }
        AppSettings.update { it.copy(treeUri = uri.toString(), folderChosen = true) }
        viewModelScope.launch {
            val ok = withContext(Dispatchers.IO) { Storage.setupFolders(app) }
            if (!ok) {
                AppSettings.update { it.copy(treeUri = "") }
                message = "No se puede escribir en esa carpeta. Elige otra."
                return@launch
            }
            message = "Listo: carpetas Audio y Video en ${Storage.treeLabel(uri.toString())}"
            pendingMove = withContext(Dispatchers.IO) { movableItems().size }
        }
    }

    fun skipFolderSetup() = AppSettings.update { it.copy(folderChosen = true) }

    fun useDefaultFolder() {
        val old = AppSettings.current.treeUri
        if (old.isNotBlank()) runCatching {
            getApplication<Application>().contentResolver.releasePersistableUriPermission(Uri.parse(old), treeFlags)
        }
        AppSettings.update { it.copy(treeUri = "", folderChosen = true) }
        message = "Se usará la carpeta predeterminada"
    }

    private fun movableItems(): List<HistoryItem> {
        val app = getApplication<Application>()
        return HistoryStore.items.value.filter {
            it.status == com.descargo.app.data.Status.DONE && it.uri != null && !Storage.isInTree(app, Uri.parse(it.uri))
        }
    }

    fun countMovable() {
        viewModelScope.launch { pendingMove = withContext(Dispatchers.IO) { movableItems().size } }
    }

    /** Moves earlier downloads into <carpeta>/Audio or <carpeta>/Video according to their type. */
    fun moveEarlierDownloads() {
        val app = getApplication<Application>()
        pendingMove = 0
        moving = true
        viewModelScope.launch {
            var ok = 0
            var failed = 0
            withContext(Dispatchers.IO) {
                for (item in movableItems()) {
                    val saved = Storage.moveIntoTree(app, Uri.parse(item.uri), item.fileName ?: "archivo", item.kind, item.site)
                    if (saved != null) {
                        HistoryStore.update(item.id) { it.copy(uri = saved.uri.toString(), fileName = saved.name, mime = saved.mime) }
                        ok++
                    } else failed++
                }
            }
            moving = false
            message = "Movidos $ok archivos a Audio/Video" + if (failed > 0) " ($failed no se pudieron: ya no existen o falta permiso)" else ""
        }
    }

    // Tag editor
    fun readTags(item: HistoryItem): Tags =
        item.uri?.let { Tagger.read(getApplication(), Uri.parse(it)) } ?: Tags(item.title, "", "")

    fun saveTags(item: HistoryItem, tags: Tags, cover: Uri?) {
        val uri = item.uri ?: return
        viewModelScope.launch {
            val err = withContext(Dispatchers.IO) {
                Tagger.write(getApplication(), Uri.parse(uri), item.fileName ?: "audio.mp3", tags, cover)
            }
            if (err == null) HistoryStore.update(item.id) { it.copy(title = tags.title.ifBlank { it.title }) }
            message = err?.let { "No se pudieron guardar las etiquetas: $it" } ?: "Etiquetas guardadas"
        }
    }
    fun retry(item: HistoryItem) {
        DownloadQueue.retry(getApplication(), item)
        message = "Reintentando: ${item.title}"
    }

    fun delete(item: HistoryItem, deleteFile: Boolean) {
        var fileOk = true
        if (deleteFile && item.uri != null) fileOk = Storage.delete(getApplication(), Uri.parse(item.uri))
        HistoryStore.remove(listOf(item.id))
        if (!fileOk) message = "Quitado del historial. El archivo no se pudo borrar: hazlo desde Archivos."
    }

    fun clearHistory() {
        HistoryStore.remove(HistoryStore.items.value.filterNot { it.isPending }.map { it.id })
        message = "Historial limpio (los archivos siguen en Descargas)"
    }

    fun updateSettings(transform: (Settings) -> Settings) {
        val before = AppSettings.current
        AppSettings.update(transform)
        if (before.subsIntervalH != AppSettings.current.subsIntervalH || before.wifiOnly != AppSettings.current.wifiOnly) {
            SubscriptionWorker.schedule(getApplication(), replace = true)
        }
    }

    fun clearSession() {
        Cookies.clear(getApplication())
        message = "Sesiones cerradas"
    }

    fun updateEngine() {
        val app = getApplication<DescargoApp>()
        app.appScope.launch {
            val ok = Engine.update(app)
            message = if (ok) "Motor actualizado" else "No se pudo actualizar el motor"
        }
    }

    companion object {
        private val URL_RE = Regex("""https?://\S+""")
        fun extractUrl(text: String?): String? =
            text?.let { URL_RE.find(it)?.value?.trimEnd('.', ',', ')', '"', '\'') }

        private val MEDIA_PATTERNS = listOf(
            "youtube.com/watch", "youtube.com/shorts/", "youtu.be/", "youtube.com/playlist",
            "youtube.com/@", "youtube.com/live/", "tiktok.com/@", "vm.tiktok.com/",
            "facebook.com/watch", "facebook.com/reel", "/videos/", "fb.watch/", "facebook.com/share/",
            "instagram.com/reel", "instagram.com/p/", "instagram.com/tv/",
            "pinterest.", "pin.it/", "/status/", "vimeo.com/", "soundcloud.com/", "twitch.tv/",
        )

        /** Heuristic for showing the browser's download button. */
        fun isMediaUrl(u: String): Boolean {
            val l = u.lowercase()
            if (l.contains("pinterest.") && !l.contains("/pin/")) return false
            if (l.contains("tiktok.com/@") && !l.contains("/video/") && !l.matches(Regex(".*tiktok\\.com/@[^/?#]+/?(\\?.*)?$"))) return false
            return MEDIA_PATTERNS.any { l.contains(it) }
        }
    }
}
