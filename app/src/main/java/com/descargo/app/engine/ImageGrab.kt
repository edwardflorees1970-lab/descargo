package com.descargo.app.engine

import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Fallback for posts that are a photo, not a video (Pinterest pins, public Instagram/X posts):
 * reads the page's Open Graph image. Only the main image; carousels need the site's private API.
 */
object ImageGrab {
    data class Found(val pageUrl: String, val title: String, val imageUrl: String, val site: String)

    // Sites hand Open Graph tags to link-preview bots even when the page itself is JS-only.
    private const val UA = "facebookexternalhit/1.1 (+http://www.facebook.com/externalhit_uatext.php)"

    private fun meta(html: String, prop: String): String? {
        val a = Regex("""<meta[^>]+(?:property|name)=["']$prop["'][^>]+content=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
        val b = Regex("""<meta[^>]+content=["']([^"']+)["'][^>]+(?:property|name)=["']$prop["']""", RegexOption.IGNORE_CASE)
        return (a.find(html) ?: b.find(html))?.groupValues?.get(1)?.let(::unescape)
    }

    private fun unescape(s: String) = s.replace("&amp;", "&").replace("&quot;", "\"").replace("&#39;", "'")
        .replace("&lt;", "<").replace("&gt;", ">")

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
