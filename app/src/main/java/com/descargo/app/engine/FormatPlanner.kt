package com.descargo.app.engine

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.roundToInt

enum class Kind(val title: String) {
    VIDEO("Video"),
    MP3("MP3"),
    FLAC("FLAC"),
    ORIGINAL("Audio original"),
    IMAGE("Imagen"),
    ;

    val isAudio get() = this == MP3 || this == FLAC || this == ORIGINAL

    companion object {
        /** Format tabs shown when analyzing a video. */
        val formatTabs = listOf(VIDEO, MP3, FLAC, ORIGINAL)
    }
}

/** One downloadable choice. [args] are yt-dlp options: flag to value (null = flag without value). */
data class DownloadOption(
    val kind: Kind,
    val label: String,
    val detail: String,
    val sizeBytes: Long?,
    val args: List<Pair<String, String?>>,
    val warning: String? = null,
    /** Video: resolution (shorter side). MP3: bitrate. Used to preselect from settings. */
    val rank: Int? = null,
)

sealed interface Analysis {
    data class Single(val info: MediaInfo, val inPlaylist: Boolean) : Analysis
    data class Playlist(val info: PlaylistInfo) : Analysis
}

data class PlaylistEntry(
    val url: String,
    val title: String,
    val durationSec: Double?,
    val thumbnail: String?,
)

data class PlaylistInfo(
    val url: String,
    val title: String,
    val uploader: String?,
    val site: String,
    val entries: List<PlaylistEntry>,
)

/** One-tap choices for batch (playlist) downloads, where per-video format lists aren't fetched. */
object Presets {
    data class Preset(val kind: Kind, val label: String, val args: List<Pair<String, String?>>)

    private fun video(h: Int?) = Preset(
        Kind.VIDEO,
        if (h == null) "Video · mejor calidad" else "Video · hasta ${h}p",
        listOf(
            "-f" to "bv*+ba/b",
            "-S" to (if (h == null) "vcodec:h264,acodec:aac" else "res:$h,vcodec:h264,acodec:aac"),
            "--merge-output-format" to "mp4",
        ),
    )

    private fun mp3(kbps: Int) = Preset(
        Kind.MP3, "MP3 $kbps kbps",
        listOf(
            "-f" to "ba/b", "-x" to null, "--audio-format" to "mp3", "--audio-quality" to "${kbps}K",
            "--embed-thumbnail" to null, "--embed-metadata" to null,
        ),
    )

    val all: List<Preset> = listOf(
        video(null), video(2160), video(1080), video(720), video(480), video(360),
        mp3(320), mp3(192), mp3(128),
        Preset(Kind.FLAC, "FLAC", listOf("-f" to "ba/b", "-x" to null, "--audio-format" to "flac", "--embed-metadata" to null)),
        Preset(Kind.ORIGINAL, "Audio original", listOf("-f" to "ba/b", "-x" to null, "--audio-format" to "best", "--embed-metadata" to null)),
    )

    fun default(videoHeight: Int) =
        all.firstOrNull { it.kind == Kind.VIDEO && it.label.endsWith(" ${videoHeight}p") } ?: all.first()
}

data class MediaInfo(
    val url: String,
    val title: String,
    val uploader: String?,
    val durationSec: Double?,
    val thumbnail: String?,
    val site: String,
    val options: Map<Kind, List<DownloadOption>>,
    val bestAudioKbps: Int?,
    val description: String? = null,
    val chapters: List<Chapter> = emptyList(),
) {
    /** Audiobook / podcast / long talk: gets chapters, summary, author and a .txt index. */
    val spoken: SpokenKind? get() = LongForm.detect(title, description, durationSec)
}

data class Chapter(val start: Double, val title: String)

enum class SpokenKind(val genre: String) { AUDIOBOOK("Audiolibro"), PODCAST("Podcast") }

object LongForm {
    const val MIN_SECONDS = 15 * 60

    private val BOOK = Regex("""audio\s?libro|audiobook|libro completo|cap[ií]tulo\s*\d|narrad[oa] por|unabridged""", RegexOption.IGNORE_CASE)
    private val PODCAST = Regex("""podcast|episodio|episode|\bep\.?\s*\d|entrevista|interview|charla|programa""", RegexOption.IGNORE_CASE)
    private val MUSIC = Regex("""\bmix\b|mixtape|full album|[áa]lbum completo|playlist|lofi|hits|enganchados|dj set|live set|\bbeats?\b""", RegexOption.IGNORE_CASE)

    /** Long (≥15 min) spoken content, guessed from title/description; null for music mixes or short media. */
    fun detect(title: String, description: String?, durationSec: Double?): SpokenKind? {
        if (durationSec == null || durationSec < MIN_SECONDS) return null
        val text = title + "\n" + (description ?: "").take(600)
        return when {
            BOOK.containsMatchIn(text) -> SpokenKind.AUDIOBOOK
            MUSIC.containsMatchIn(title) -> null
            // 15–20 min without podcast words could be a long song/video; from 20 min assume spoken.
            PODCAST.containsMatchIn(text) || durationSec >= 20 * 60 -> SpokenKind.PODCAST
            else -> null
        }
    }

    /** Plain-text index saved next to the audio: author, duration, chapters and summary. */
    fun notes(info: MediaInfo, kind: SpokenKind): String = buildString {
        appendLine(info.title)
        appendLine("${kind.genre} · ${info.uploader ?: "Autor desconocido"}")
        info.durationSec?.let { appendLine("Duración: ${clock(it)}") }
        appendLine("Origen: ${info.url}")
        if (info.chapters.isNotEmpty()) {
            appendLine()
            appendLine("CAPÍTULOS")
            info.chapters.forEach { appendLine("${clock(it.start)}  ${it.title}") }
        }
        if (!info.description.isNullOrBlank()) {
            appendLine()
            appendLine("RESUMEN")
            appendLine(info.description.trim())
        }
    }

    fun clock(sec: Double): String {
        val s = sec.toLong()
        return String.format(java.util.Locale.US, "%d:%02d:%02d", s / 3600, s % 3600 / 60, s % 60)
    }
}

private data class Fmt(
    val id: String,
    val ext: String,
    val vcodec: String?,
    val acodec: String?,
    val height: Int,
    val fps: Int,
    val tbr: Double,
    val abr: Double,
    val size: Long?,
    val note: String,
) {
    val hasVideo get() = vcodec != "none" && (vcodec != null || height > 0 || acodec == null)
    val hasAudio get() = acodec != "none" && (acodec != null || vcodec == null)
    val audioOnly get() = !hasVideo && hasAudio
    val combined get() = hasVideo && hasAudio && acodec != null

    val videoCodecName: String
        get() = when {
            vcodec == null -> ext.uppercase()
            vcodec.startsWith("avc") || vcodec.startsWith("h264") -> "H.264"
            vcodec.startsWith("vp09") || vcodec.startsWith("vp9") -> "VP9"
            vcodec.startsWith("av01") -> "AV1"
            vcodec.startsWith("hev") || vcodec.startsWith("hvc") || vcodec.startsWith("h265") -> "H.265"
            else -> vcodec.substringBefore('.').uppercase()
        }

    val audioCodecName: String
        get() = when {
            acodec == null -> ext.uppercase()
            acodec.startsWith("opus") -> "Opus"
            acodec.startsWith("mp4a") || acodec.startsWith("aac") -> "AAC"
            acodec.startsWith("mp3") -> "MP3"
            acodec.startsWith("vorbis") -> "Vorbis"
            acodec.startsWith("flac") -> "FLAC"
            acodec.startsWith("ec-3") || acodec.startsWith("ac-3") -> "Dolby"
            else -> acodec.substringBefore('.').uppercase()
        }
}

/**
 * Turns yt-dlp's format list into Snaptube-style choices:
 *  - Video: one entry per resolution (+fps), best stream for that size, merged with best audio to MP4.
 *  - MP3: selectable bitrates, encoded from the best audio stream.
 *  - FLAC: lossless container of a chosen source stream (cannot add quality the source lacks).
 *  - Original: the audio stream as-is, no re-encode (true best quality).
 */
object FormatPlanner {

    fun plan(url: String, j: JSONObject): MediaInfo {
        val duration = j.optNum("duration")
        val formats = parseFormats(j.optJSONArray("formats"), duration)
        val site = j.optStringOrNull("extractor_key") ?: j.optStringOrNull("extractor") ?: "Web"

        val audioOnly = formats.filter { it.audioOnly }
            .sortedWith(compareByDescending<Fmt> { it.abr }.thenByDescending { it.ext == "m4a" })
        val bestAudio = audioOnly.firstOrNull()
        // For MP4 merge prefer AAC (m4a), it plays everywhere.
        val bestAudioForMp4 = audioOnly.firstOrNull { it.ext == "m4a" } ?: bestAudio
        val bestAudioKbps = bestAudio?.abr?.takeIf { it > 0 }?.roundToInt()
            ?: formats.filter { it.combined }.maxOfOrNull { it.abr }?.takeIf { it > 0 }?.roundToInt()

        val options = linkedMapOf(
            Kind.VIDEO to videoOptions(formats, bestAudioForMp4),
            Kind.MP3 to mp3Options(duration, bestAudioKbps),
            Kind.FLAC to flacOptions(audioOnly, duration),
            Kind.ORIGINAL to originalOptions(audioOnly),
        )

        return MediaInfo(
            url = j.optString("webpage_url").ifBlank { url },
            title = j.optString("title").ifBlank { "video" },
            uploader = j.optStringOrNull("uploader") ?: j.optStringOrNull("channel"),
            durationSec = duration,
            thumbnail = j.optStringOrNull("thumbnail"),
            site = site,
            options = options,
            bestAudioKbps = bestAudioKbps,
            description = j.optStringOrNull("description"),
            chapters = j.optJSONArray("chapters")?.let { arr ->
                (0 until arr.length()).mapNotNull { i ->
                    val c = arr.optJSONObject(i) ?: return@mapNotNull null
                    Chapter(c.optDouble("start_time", 0.0), c.optString("title").ifBlank { "Capítulo ${i + 1}" })
                }
            }.orEmpty(),
        )
    }

    fun playlist(url: String, j: JSONObject): PlaylistInfo {
        val arr = j.optJSONArray("entries") ?: JSONArray()
        val entries = (0 until arr.length()).mapNotNull { i ->
            val e = arr.optJSONObject(i) ?: return@mapNotNull null
            val id = e.optStringOrNull("id")
            val link = e.optStringOrNull("webpage_url")
                ?: e.optStringOrNull("url")?.takeIf { it.startsWith("http") }
                ?: if (id != null && e.optString("ie_key").equals("Youtube", true))
                    "https://www.youtube.com/watch?v=$id" else null
            link ?: return@mapNotNull null
            val thumbs = e.optJSONArray("thumbnails")
            val thumb = e.optStringOrNull("thumbnail")
                ?: thumbs?.optJSONObject(thumbs.length() - 1)?.optStringOrNull("url")
            PlaylistEntry(
                url = link,
                title = e.optStringOrNull("title") ?: id ?: link,
                durationSec = e.optNum("duration"),
                thumbnail = thumb,
            )
        }
        return PlaylistInfo(
            url = j.optStringOrNull("webpage_url") ?: url,
            title = j.optStringOrNull("title") ?: "Lista",
            uploader = j.optStringOrNull("uploader") ?: j.optStringOrNull("channel"),
            site = j.optStringOrNull("extractor_key") ?: "Web",
            entries = entries,
        )
    }

    private fun parseFormats(arr: JSONArray?, duration: Double?): List<Fmt> {
        if (arr == null) return emptyList()
        val out = ArrayList<Fmt>(arr.length())
        for (i in 0 until arr.length()) {
            val f = arr.optJSONObject(i) ?: continue
            val ext = f.optString("ext")
            val protocol = f.optString("protocol")
            val note = f.optString("format_note")
            if (ext == "mhtml" || protocol == "mhtml" || note.contains("storyboard", true)) continue
            val tbr = f.optNum("tbr") ?: 0.0
            val size = f.optLongOrNull("filesize") ?: f.optLongOrNull("filesize_approx")
                ?: if (tbr > 0 && duration != null) (tbr * 125 * duration).toLong() else null
            out += Fmt(
                id = f.optString("format_id"),
                ext = ext,
                vcodec = f.optStringOrNull("vcodec"),
                acodec = f.optStringOrNull("acodec"),
                height = quality(f.optNum("width")?.toInt() ?: 0, f.optNum("height")?.toInt() ?: 0),
                fps = f.optNum("fps")?.roundToInt() ?: 0,
                tbr = tbr,
                abr = f.optNum("abr") ?: 0.0,
                size = size,
                note = note,
            )
        }
        return out
    }

    /** Resolution label uses the shorter side, so a vertical 1080x1920 clip reads as 1080p. */
    private fun quality(w: Int, h: Int) = if (w > 0 && h > 0) minOf(w, h) else h

    private fun videoOptions(formats: List<Fmt>, audio: Fmt?): List<DownloadOption> {
        val video = formats.filter { it.hasVideo }
        val result = mutableListOf<DownloadOption>()

        result += DownloadOption(
            kind = Kind.VIDEO,
            label = "Mejor calidad",
            detail = "Automático · MP4",
            sizeBytes = null,
            args = listOf(
                "-f" to "bv*+ba[ext=m4a]/bv*+ba/b",
                "--merge-output-format" to "mp4",
            ),
        )
        if (video.isEmpty()) return result

        // One entry per (height, high-fps). Up to 1080p prefer H.264 for compatibility;
        // above that only VP9/AV1 usually exist, so take the highest bitrate.
        val groups = video.groupBy { it.height to (it.fps >= 50) }
        for ((key, list) in groups.entries.sortedWith(
            compareByDescending<Map.Entry<Pair<Int, Boolean>, List<Fmt>>> { it.key.first }
                .thenByDescending { it.key.second }
        )) {
            val (height, highFps) = key
            val preferH264 = height in 1..1080
            val pick = list.sortedWith(
                compareByDescending<Fmt> { preferH264 && it.videoCodecName == "H.264" }
                    // Separate video + best audio beats legacy muxed streams (which carry low-bitrate audio).
                    .thenByDescending { audio == null || !it.combined }
                    .thenByDescending { it.tbr }
            ).first()

            val needsAudio = !pick.combined && audio != null
            val size = when {
                pick.size == null -> null
                needsAudio -> pick.size + (audio?.size ?: 0)
                else -> pick.size
            }
            val label = if (height > 0) "${height}p${if (highFps) pick.fps.toString() else ""}" else "Original"
            val args = if (needsAudio) listOf(
                "-f" to "${pick.id}+${audio!!.id}/${pick.id}+ba/${pick.id}",
                "--merge-output-format" to "mp4",
            ) else listOf("-f" to pick.id)
            val ext = if (needsAudio) "MP4" else pick.ext.uppercase()
            val codec = pick.videoCodecName.takeIf { it != ext }
            result += DownloadOption(
                kind = Kind.VIDEO,
                label = label + qualityTag(height),
                rank = height,
                detail = listOfNotNull(ext, codec).joinToString(" · "),
                sizeBytes = size,
                args = args,
            )
        }
        return result
    }

    private fun qualityTag(h: Int) = when {
        h >= 4320 -> "  8K"
        h >= 2160 -> "  4K"
        h >= 1440 -> "  2K"
        h >= 720 -> "  HD"
        else -> ""
    }

    private fun mp3Options(duration: Double?, sourceKbps: Int?): List<DownloadOption> =
        listOf(320, 256, 192, 160, 128, 96).map { kbps ->
            DownloadOption(
                kind = Kind.MP3,
                label = "MP3 $kbps kbps",
                rank = kbps,
                detail = if (kbps == 320) "Máxima calidad MP3" else "CBR",
                sizeBytes = duration?.let { (it * kbps * 125).toLong() },
                args = listOf(
                    "-f" to "ba/b",
                    "-x" to null,
                    "--audio-format" to "mp3",
                    "--audio-quality" to "${kbps}K",
                    "--embed-thumbnail" to null,
                    "--embed-metadata" to null,
                ),
                warning = if (sourceKbps != null && kbps > sourceKbps + 32)
                    "La fuente es ~$sourceKbps kbps: este bitrate no mejora el sonido, solo pesa más." else null,
            )
        }

    private fun flacOptions(audioOnly: List<Fmt>, duration: Double?): List<DownloadOption> {
        // FLAC at 48 kHz stereo from a lossy source averages ~900 kbps.
        val estimate = duration?.let { (it * 900 * 125).toLong() }
        val base = listOf(
            "-x" to null,
            "--audio-format" to "flac",
            "--embed-metadata" to null,
        )
        val result = mutableListOf(
            DownloadOption(
                kind = Kind.FLAC,
                label = "FLAC · mejor fuente",
                detail = "Sin pérdida desde la mejor pista disponible",
                sizeBytes = estimate,
                args = listOf("-f" to "ba/b") + base,
            )
        )
        audioOnly.distinctBy { it.id }.take(6).forEach { f ->
            result += DownloadOption(
                kind = Kind.FLAC,
                label = "FLAC desde ${f.audioCodecName} ${f.abr.roundToInt()} kbps",
                detail = listOf(f.note, "id ${f.id}").filter { it.isNotBlank() }.joinToString(" · "),
                sizeBytes = estimate,
                args = listOf("-f" to f.id) + base,
            )
        }
        return result
    }

    private fun originalOptions(audioOnly: List<Fmt>): List<DownloadOption> {
        val base = listOf("-x" to null, "--audio-format" to "best", "--embed-metadata" to null)
        val result = mutableListOf(
            DownloadOption(
                kind = Kind.ORIGINAL,
                label = "Mejor audio original",
                detail = "Sin recodificar (Opus/AAC)",
                sizeBytes = audioOnly.firstOrNull()?.size,
                args = listOf("-f" to "ba/b") + base,
            )
        )
        audioOnly.distinctBy { it.id }.take(8).forEach { f ->
            val ext = if (f.audioCodecName == "Opus") "OPUS" else f.ext.uppercase()
            result += DownloadOption(
                kind = Kind.ORIGINAL,
                label = "$ext ${f.abr.roundToInt()} kbps",
                detail = listOf(f.audioCodecName, f.note).filter { it.isNotBlank() }.joinToString(" · "),
                sizeBytes = f.size,
                args = listOf("-f" to f.id) + base,
            )
        }
        return result
    }
}

private fun JSONObject.optNum(key: String): Double? =
    if (has(key) && !isNull(key)) optDouble(key).takeIf { !it.isNaN() } else null

private fun JSONObject.optLongOrNull(key: String): Long? = optNum(key)?.toLong()

private fun JSONObject.optStringOrNull(key: String): String? =
    if (has(key) && !isNull(key)) optString(key).ifBlank { null } else null
