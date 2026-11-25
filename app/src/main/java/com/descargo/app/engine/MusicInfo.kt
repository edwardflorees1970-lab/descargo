package com.descargo.app.engine

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Looks songs up in free public databases to complete their tags:
 *  - MusicBrainz: real title/artist, album, year (score ≥ 90 only, to avoid wrong matches).
 *  - Cover Art Archive: square album cover.
 *  - LRCLIB: plain lyrics.
 */
object MusicInfo {
    private const val TAG = "MusicInfo"
    private const val UA = "Descargo/1.4 (Android app; https://musicbrainz.org/doc/MusicBrainz_API)"

    data class Match(
        val title: String,
        val artist: String,
        val album: String?,
        val year: String?,
        val releaseId: String?,
        val releaseGroupId: String? = null,
    )

    private val NOISE = Regex(
        """\s*[(\[](official\s*(music\s*)?(video|audio|visualizer)|video\s*oficial|audio\s*oficial|lyrics?|letra|lyric video|hd|hq|4k|visualizer|remastered( \d{4})?)[)\]]""",
        RegexOption.IGNORE_CASE,
    )

    /** Strips "(Official Video)", "[Lyrics]"… and YouTube's " - Topic" channel suffix. */
    fun clean(title: String, artist: String): Pair<String, String> {
        var t = NOISE.replace(title, "").trim()
        var a = artist.removeSuffix(" - Topic").removeSuffix("VEVO").trim()
        // Title may still be "Artist - Song" when the artist tag came from the channel.
        if (" - " in t && (a.isBlank() || t.startsWith(a, ignoreCase = true))) {
            a = t.substringBefore(" - ").trim()
            t = t.substringAfter(" - ").trim()
        }
        return t to a
    }

    fun lookup(title: String, artist: String): Match? = runCatching {
        if (title.isBlank() || artist.isBlank()) return null
        val q = "recording:\"${title.replace("\"", "")}\" AND artist:\"${artist.replace("\"", "")}\""
        val json = getJson("https://musicbrainz.org/ws/2/recording?fmt=json&limit=3&query=" + enc(q)) as? JSONObject
            ?: return null
        val recs = json.optJSONArray("recordings") ?: return null
        val rec = (0 until recs.length()).map { recs.getJSONObject(it) }
            .firstOrNull { it.optInt("score") >= 90 } ?: return null
        val credit = rec.optJSONArray("artist-credit")?.let { ac ->
            (0 until ac.length()).joinToString("") { i ->
                val c = ac.getJSONObject(i)
                c.optJSONObject("artist")?.optString("name").orEmpty() + c.optString("joinphrase")
            }
        }?.ifBlank { null } ?: artist
        // Prefer the original studio album: official, type Album, no Live/Compilation, earliest date.
        val releases = rec.optJSONArray("releases")
        val rel = releases?.let { r ->
            val all = (0 until r.length()).map { r.getJSONObject(it) }
            fun rank(o: JSONObject): Int {
                val rg = o.optJSONObject("release-group")
                val secondary = rg?.optJSONArray("secondary-types")?.length() ?: 0
                var score = 0
                if (o.optString("status") == "Official") score += 4
                if (rg?.optString("primary-type") == "Album") score += 2
                if (secondary == 0) score += 3
                return score
            }
            all.sortedWith(compareByDescending<JSONObject> { rank(it) }.thenBy { it.optString("date").ifBlank { "9999" } })
                .firstOrNull()
        }
        Match(
            title = rec.optString("title").ifBlank { title },
            artist = credit,
            album = rel?.optString("title")?.ifBlank { null },
            year = (rel?.optString("date") ?: rec.optString("first-release-date")).take(4).ifBlank { null },
            releaseId = rel?.optString("id")?.ifBlank { null },
            releaseGroupId = rel?.optJSONObject("release-group")?.optString("id")?.ifBlank { null },
        )
    }.onFailure { Log.w(TAG, "musicbrainz failed", it) }.getOrNull()

    /** Downloads the front cover (500 px) to [dest]: release first, then its release group. */
    fun cover(match: Match, dest: File): Boolean {
        val urls = listOfNotNull(
            match.releaseId?.let { "https://coverartarchive.org/release/$it/front-500" },
            match.releaseGroupId?.let { "https://coverartarchive.org/release-group/$it/front-500" },
        )
        for (u in urls) runCatching {
            val c = open(u)
            if (c.responseCode == 200) {
                c.inputStream.use { i -> dest.outputStream().use { i.copyTo(it) } }
                if (dest.length() > 1000) return true
            }
        }
        return false
    }

    fun lyrics(title: String, artist: String, durationSec: Int?): String? = runCatching {
        val base = "https://lrclib.net/api/get?track_name=${enc(title)}&artist_name=${enc(artist)}" +
            (durationSec?.let { "&duration=$it" } ?: "")
        val exact = getJson(base) as? JSONObject
        exact?.optString("plainLyrics")?.ifBlank { null }?.let { return it }
        val found = getJson("https://lrclib.net/api/search?q=" + enc("$artist $title")) as? JSONArray ?: return null
        (0 until found.length()).map { found.getJSONObject(it) }
            .firstOrNull { it.optString("plainLyrics").isNotBlank() }?.optString("plainLyrics")
    }.getOrNull()

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    private fun open(url: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 15_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", UA)
            setRequestProperty("Accept", "application/json")
        }

    @Volatile private var lastMb = 0L

    private fun getJson(url: String): Any? {
        var c = open(url)
        if ("musicbrainz.org" in url) {
            // MusicBrainz allows ~1 request/second; wait and retry once when it says "busy".
            synchronized(this) {
                val wait = 1100 - (System.currentTimeMillis() - lastMb)
                if (wait > 0) Thread.sleep(wait)
                lastMb = System.currentTimeMillis()
            }
            if (c.responseCode == 503) {
                Thread.sleep(1500)
                c = open(url)
            }
        }
        if (c.responseCode != 200) return null
        val text = c.inputStream.bufferedReader().use { it.readText() }
        return if (text.trimStart().startsWith("[")) JSONArray(text) else JSONObject(text)
    }
}
