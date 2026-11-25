package com.descargo.app.engine

import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Fallback for posts that are a photo, not a video (Pinterest pins, public Instagram/X posts):
 * reads the page's Open Graph image. Only the main image; carousels need the site's private API.
 */
object ImageGrab {
    data class Found(
        val pageUrl: String,
        val title: String,
        val imageUrl: String,
        val site: String,
        /** Every full-size photo of the post (carousels have several); first = [imageUrl]. */
        val images: List<String> = listOf(imageUrl),
    )

    // Instagram's "stp" parameter: a "c<x>.<y>.<w>.<h>" step means a cropped copy.
    private val CROPPED = Regex("""stp=(?:[^&]*_)?c\d+\.\d+""")

    /**
     * Best photo from yt-dlp's thumbnail list for one post: the largest one that isn't a crop.
     * Instagram lists its uncropped original last, the 640px square crops before it.
     */
    fun bestThumbnail(json: JSONObject): String? {
        val arr = json.optJSONArray("thumbnails") ?: return json.optString("thumbnail").ifBlank { null }
        val all = (0 until arr.length()).map { arr.getJSONObject(it) }.filter { it.optString("url").startsWith("http") }
        if (all.isEmpty()) return json.optString("thumbnail").ifBlank { null }
        val uncropped = all.filterNot { CROPPED.containsMatchIn(it.optString("url")) }.ifEmpty { all }
        val sized = uncropped.filter { it.optInt("width") > 0 && it.optInt("height") > 0 }
        val pick = sized.maxByOrNull { it.optInt("width") * it.optInt("height") } ?: uncropped.last()
        return pick.optString("url")
    }

    /** Photos from yt-dlp's post data (works for Instagram photo posts, incl. carousels). */
    fun fromInfo(pageUrl: String, json: JSONObject): Found? {
        val posts = json.optJSONArray("entries")?.let { e -> (0 until e.length()).mapNotNull { e.optJSONObject(it) } }
            ?: listOf(json)
        val images = posts.mapNotNull(::bestThumbnail).distinct()
        if (images.isEmpty()) return null
        val first = posts.first()
        val title = listOf(json.optString("description"), first.optString("description"), json.optString("title"), first.optString("title"))
            .map { it.lineSequence().firstOrNull { l -> l.isNotBlank() }.orEmpty().trim() }
            .firstOrNull { it.isNotBlank() && !it.startsWith("Video by", true) && !it.startsWith("Post by", true) }
            ?: (first.optString("uploader").ifBlank { json.optString("uploader") }.ifBlank { "imagen" })
        val site = json.optString("extractor_key").ifBlank { first.optString("extractor_key") }.ifBlank { "Web" }
        return Found(pageUrl, title.take(120), images.first(), site, images)
    }

    // Sites hand Open Graph tags to link-preview bots even when the page itself is JS-only.
    private const val UA = "facebookexternalhit/1.1 (+http://www.facebook.com/externalhit_uatext.php)"

    private fun meta(html: String, prop: String): String? {
        val a = Regex("""<meta[^>]+(?:property|name)=["']$prop["'][^>]+content=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
        val b = Regex("""<meta[^>]+content=["']([^"']+)["'][^>]+(?:property|name)=["']$prop["']""", RegexOption.IGNORE_CASE)
        return (a.find(html) ?: b.find(html))?.groupValues?.get(1)?.let(::unescape)
    }

    private val NUMERIC_ENTITY = Regex("""&#(x[0-9a-fA-F]+|\d+);""")

    private fun unescape(s: String) = NUMERIC_ENTITY.replace(s) { m ->
        val v = m.groupValues[1]
        val cp = if (v.startsWith("x")) v.drop(1).toIntOrNull(16) else v.toIntOrNull()
        cp?.let { String(Character.toChars(it)) } ?: m.value
    }.replace("&quot;", "\"").replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&")

    fun find(url: String): Found? = runCatching {
        val c = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 15_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", UA)
        }
        if (c.responseCode !in 200..299) return null
        // Read up to ~2 MB (Pinterest pages are big; og tags may sit far down).
        val html = c.inputStream.bufferedReader().use { r ->
            val sb = StringBuilder()
            val buf = CharArray(64 * 1024)
            while (sb.length < 2_000_000) {
                val n = r.read(buf)
                if (n <= 0) break
                sb.append(buf, 0, n)
            }
            sb.toString()
        }
        var img = meta(html, "og:image") ?: meta(html, "twitter:image") ?: return null
        // Pinterest serves resized copies; the same path under /originals/ is full size.
        img = img.replace(Regex("""i\.pinimg\.com/\d+x/"""), "i.pinimg.com/originals/")
        val title = meta(html, "og:title") ?: Regex("<title>([^<]+)</title>").find(html)?.groupValues?.get(1)?.let(::unescape)
            ?: "imagen"
        val host = URL(c.url.toString()).host.removePrefix("www.").substringBefore('.').replaceFirstChar { it.uppercase() }
        Found(c.url.toString(), title.trim().take(120), img, host)
    }.getOrNull()

    /** Downloads [imageUrl] into [dir]; falls back from /originals/ to the resized copy if needed. */
    fun download(imageUrl: String, dir: File, baseName: String): File? {
        val candidates = listOf(imageUrl, imageUrl.replace("/originals/", "/736x/")).distinct()
        for (u in candidates) runCatching {
            val c = (URL(u).openConnection() as HttpURLConnection).apply {
                connectTimeout = 10_000
                readTimeout = 30_000
                setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) Descargo")
            }
            if (c.responseCode == 200) {
                val ext = when {
                    c.contentType?.contains("png") == true -> "png"
                    c.contentType?.contains("webp") == true -> "webp"
                    c.contentType?.contains("gif") == true -> "gif"
                    else -> "jpg"
                }
                val safe = baseName.replace(Regex("""[\\/:*?"<>|]"""), "").take(100).ifBlank { "imagen" }
                val f = File(dir, "$safe.$ext")
                c.inputStream.use { i -> f.outputStream().use { i.copyTo(it) } }
                if (f.length() > 0) return f
            }
        }
        return null
    }
}
