package com.descargo.app.engine

import android.content.Context
import android.util.Log
import com.descargo.app.data.AppSettings
import com.descargo.app.data.Cookies
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONObject
import java.io.File

sealed interface EngineState {
    data object Starting : EngineState
    data class Updating(val version: String?) : EngineState
    data class Ready(val version: String?) : EngineState
    data class Failed(val message: String) : EngineState
}

/**
 * Thin layer over youtubedl-android (yt-dlp + python + ffmpeg + QuickJS bundled as native libs).
 */
object Engine {
    private const val TAG = "Engine"
    private const val PREFS = "engine"
    private const val KEY_LAST_UPDATE = "last_update"
    private const val UPDATE_EVERY_MS = 12L * 60 * 60 * 1000

    // yt-dlp >= 2025.11.12 fetches the YouTube JS-challenge solver ("ejs") with this flag.
    private const val REMOTE_COMPONENTS_MIN_VERSION = "2025.11.12"

    private val _state = MutableStateFlow<EngineState>(EngineState.Starting)
    val state: StateFlow<EngineState> = _state

    private val ready = CompletableDeferred<Unit>()

    @Volatile
    private var supportsRemoteComponents = false

    /** Blocking. Call from a background thread (done in Application.onCreate). */
    fun start(ctx: Context) {
        try {
            YoutubeDL.getInstance().init(ctx)
            FFmpeg.getInstance().init(ctx)
        } catch (e: Exception) {
            Log.e(TAG, "init failed", e)
            _state.value = EngineState.Failed(e.message ?: "No se pudo iniciar el motor")
            ready.completeExceptionally(e)
            return
        }
        refreshVersion(ctx)
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val last = prefs.getLong(KEY_LAST_UPDATE, 0L)
        if (System.currentTimeMillis() - last > UPDATE_EVERY_MS) {
            update(ctx)
        }
        _state.value = EngineState.Ready(YoutubeDL.getInstance().version(ctx))
        ready.complete(Unit)
    }

    /** Blocking. Updates the yt-dlp script (sites change often, YouTube especially). */
    fun update(ctx: Context): Boolean {
        _state.value = EngineState.Updating(YoutubeDL.getInstance().version(ctx))
        val ok = try {
            val channel = if (AppSettings.current.nightlyEngine) YoutubeDL.UpdateChannel.NIGHTLY
            else YoutubeDL.UpdateChannel.STABLE
            YoutubeDL.getInstance().updateYoutubeDL(ctx, channel)
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putLong(KEY_LAST_UPDATE, System.currentTimeMillis()).apply()
            true
        } catch (e: Exception) {
            Log.w(TAG, "yt-dlp update failed", e)
            false
        }
        refreshVersion(ctx)
        _state.value = EngineState.Ready(YoutubeDL.getInstance().version(ctx))
        return ok
    }

    private fun refreshVersion(ctx: Context) {
        val v = YoutubeDL.getInstance().version(ctx)
        supportsRemoteComponents = v != null && v >= REMOTE_COMPONENTS_MIN_VERSION
    }

    suspend fun awaitReady() = ready.await()

    fun baseRequest(ctx: Context, url: String, playlist: Boolean = false): YoutubeDLRequest {
        val r = YoutubeDLRequest(url)
        r.addOption(if (playlist) "--yes-playlist" else "--no-playlist")
        r.addOption("--no-warnings")
        r.addOption("--cache-dir", File(ctx.cacheDir, "ytdlp-cache").absolutePath)
        if (supportsRemoteComponents && isYouTube(url)) {
            r.addOption("--remote-components", "ejs:github")
        }
        if (AppSettings.current.useCookies) {
            Cookies.export(ctx)?.let { r.addOption("--cookies", it.absolutePath) }
        }
        return r
    }

    private fun isYouTube(url: String) =
        url.contains("youtube.com", true) || url.contains("youtu.be", true)

    private val PLAYLIST_PATTERNS = listOf(
        Regex("""youtube\.com/playlist""", RegexOption.IGNORE_CASE),
        Regex("""youtube\.com/(@[^/?#]+|channel/|c/|user/)""", RegexOption.IGNORE_CASE),
        Regex("""tiktok\.com/@[^/?#]+/?(\?|$)""", RegexOption.IGNORE_CASE),
        Regex("""soundcloud\.com/[^/]+/sets/""", RegexOption.IGNORE_CASE),
    )

    fun looksLikePlaylist(url: String) = PLAYLIST_PATTERNS.any { it.containsMatchIn(url) }

    private val CHANNEL_ROOT = Regex(
        """^(https?://(?:www\.|m\.)?youtube\.com/(?:@[^/?#]+|channel/[^/?#]+|c/[^/?#]+|user/[^/?#]+))/?(?:\?.*)?$""",
        RegexOption.IGNORE_CASE,
    )

    /** A bare channel URL lists its tabs (Videos/Shorts/Live); point it at the Videos tab instead. */
    fun normalizeChannel(url: String): String =
        CHANNEL_ROOT.find(url)?.let { it.groupValues[1] + "/videos" } ?: url

    /** Options that depend on current settings, applied when a download actually starts. */
    fun runtimeArgs(kind: Kind, url: String, spoken: Boolean = false): List<Pair<String, String?>> = buildList {
        val s = AppSettings.current
        if (s.normalize && (kind == Kind.MP3 || kind == Kind.FLAC)) {
            // EBU R128 loudness so every track plays at a similar volume.
            add("--postprocessor-args" to "ExtractAudio:-af loudnorm=I=-16:TP=-1.5:LRA=11")
        }
        if (s.rateLimit.isNotBlank()) add("--limit-rate" to s.rateLimit)
        if (isYouTube(url) && s.sponsorBlock) {
            add("--sponsorblock-remove" to "sponsor,selfpromo,interaction")
        }
        if (s.chapters && (kind == Kind.VIDEO || kind == Kind.ORIGINAL)) add("--embed-chapters" to null)
        if (s.splitArtist && kind.isAudio && !spoken) {
            // "Artista - Canción" → artist + title tags (no-op when the title has no " - ").
            add("--parse-metadata" to "title:%(artist)s - %(title)s")
        }
    }

    /** "1:30", "01:02:03" or "90" → seconds; null if invalid. */
    fun parseTime(t: String): Int? {
        val parts = t.trim().split(':')
        if (parts.isEmpty() || parts.size > 3 || parts.any { it.isBlank() || it.any { c -> !c.isDigit() } }) return null
        return parts.fold(0) { acc, p -> acc * 60 + p.toInt() }
    }

    /** yt-dlp options to download only [start, end] seconds. Cuts on keyframes forced for accuracy. */
    fun clipArgs(start: Int, end: Int): List<Pair<String, String?>> = listOf(
        "--download-sections" to "*$start-$end",
        "--force-keyframes-at-cuts" to null,
    )

    /** A single video URL that also carries a playlist (YouTube `watch?v=…&list=…`). */
    fun hasPlaylistParam(url: String) = isYouTube(url) && url.contains("list=")

    /**
     * Blocking. Single videos get the full format list; playlists/channels are listed flat
     * (titles only, fast) and capped at [PLAYLIST_LIMIT] entries.
     */
    fun analyze(ctx: Context, rawUrl: String, forcePlaylist: Boolean = false, limit: Int = PLAYLIST_LIMIT): Analysis {
        val url = normalizeChannel(rawUrl)
        val playlist = forcePlaylist || looksLikePlaylist(url)
        val resp = withNetworkRetry {
            val req = baseRequest(ctx, url, playlist)
            if (playlist) {
                req.addOption("--flat-playlist")
                req.addOption("--playlist-end", limit)
            }
            req.addOption("--dump-single-json")
            YoutubeDL.getInstance().execute(req, null, null)
        }
        val jsonLine = resp.out.lineSequence().lastOrNull { it.trimStart().startsWith("{") }
            ?: throw IllegalStateException("Respuesta vacía del motor")
        val json = JSONObject(jsonLine)
        return if (json.optString("_type") == "playlist") {
            Analysis.Playlist(FormatPlanner.playlist(url, json))
        } else {
            Analysis.Single(FormatPlanner.plan(url, json), hasPlaylistParam(url))
        }
    }

    private const val PLAYLIST_LIMIT = 500

    /** Photo posts: yt-dlp still reads the post's images even when there is no video. */
    fun findImages(ctx: Context, url: String): ImageGrab.Found? = runCatching {
        val resp = withNetworkRetry(attempts = 3) {
            val req = baseRequest(ctx, url, playlist = true)
            req.addOption("--ignore-no-formats-error")
            req.addOption("--dump-single-json")
            YoutubeDL.getInstance().execute(req, null, null)
        }
        val line = resp.out.lineSequence().lastOrNull { it.trimStart().startsWith("{") } ?: return null
        ImageGrab.fromInfo(url, JSONObject(line))
    }.onFailure { Log.w(TAG, "findImages failed: ${it.message?.take(200)}") }.getOrNull()

    /**
     * Some CDNs (TikTok especially) drop connections mid-response; the page fetch then fails with
     * "[SSL] record layer failure" / connection reset. A fresh attempt usually succeeds.
     */
    fun <T> withNetworkRetry(
        attempts: Int = 4,
        shouldStop: () -> Boolean = { false },
        block: (attempt: Int) -> T,
    ): T {
        var last: Exception? = null
        for (i in 0 until attempts) {
            try {
                return block(i)
            } catch (e: InterruptedException) {
                throw e
            } catch (e: Exception) {
                last = e
                val bot = isBotCheck(e)
                if (shouldStop() || !(bot || isTransient(e)) || i == attempts - 1) throw e
                Log.w(TAG, "transient error (bot=$bot), retry ${i + 1}: ${e.message?.take(160)}")
                // YouTube's "confirm you're not a bot" usually clears after a short pause.
                Thread.sleep(if (bot) 20_000L * (i + 1) else 800L * (i + 1))
            }
        }
        throw last!!
    }

    private fun isBotCheck(e: Exception): Boolean {
        val m = e.message ?: return false
        return m.contains("not a bot", true) && !m.contains("confirm your age", true)
    }

    private fun isTransient(e: Exception): Boolean {
        val m = e.message ?: return false
        return listOf(
            "record layer failure", "SSLError", "Connection reset", "timed out",
            "Remote end closed connection", "IncompleteRead", "EOF occurred",
            "No address associated with hostname", "Temporary failure in name resolution",
            "Network is unreachable",
        ).any { m.contains(it, ignoreCase = true) }
    }

    fun friendlyError(t: Throwable): String {
        val raw = t.message ?: t.toString()
        Log.w(TAG, "yt-dlp error: $raw")
        val line = raw.lineSequence()
            .map { it.substringAfter("ERROR:").trim() }
            .firstOrNull { it.isNotBlank() && !it.startsWith("WARNING") }
            ?: raw
        return when {
            raw.contains("Unsupported URL", true) -> "Enlace no soportado."
            raw.contains("confirm your age", true) || raw.contains("age-restricted", true) ->
                "Restricción de edad: inicia sesión en el Navegador y activa «Usar mi sesión» en Ajustes."
            raw.contains("Sign in to confirm", true) ->
                "YouTube pidió verificación anti-bot. Espera unos minutos, actualiza el motor o usa tu sesión (Ajustes)."
            raw.contains("Private video", true) || raw.contains("login required", true) ||
                raw.contains("log in", true) ->
                "Contenido privado: inicia sesión en el Navegador y activa «Usar mi sesión» en Ajustes."
            raw.contains("HTTP Error 429", true) -> "Demasiadas solicitudes. Prueba más tarde."
            raw.contains("Unable to resolve host", true) || raw.contains("getaddrinfo", true) ||
                raw.contains("No address associated with hostname", true) ->
                "Sin conexión o DNS caído. Revisa tu internet e intenta de nuevo."
            isTransient(t as? Exception ?: Exception(raw)) ->
                "La conexión se cortó varias veces. Revisa tu internet e intenta de nuevo."
            else -> line.take(240)
        }
    }
}
