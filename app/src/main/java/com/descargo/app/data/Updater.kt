package com.descargo.app.data

import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Self-update from the public GitHub releases of the project.
 * Release tags look like "v1.5"; assets are named Descargo-arm64.apk / -arm32.apk / -x86_64.apk.
 */
object Updater {
    const val REPO = "edwardflorees1970-lab/descargo"
    private const val CHECK_EVERY_MS = 24L * 60 * 60 * 1000

    data class Release(val version: String, val notes: String, val apkUrl: String, val sizeBytes: Long)

    fun currentVersion(ctx: Context): String =
        ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "0"

    /** Asset name for this phone's CPU. */
    private fun assetName(): String = when (Build.SUPPORTED_ABIS.firstOrNull()) {
        "arm64-v8a" -> "Descargo-arm64.apk"
        "armeabi-v7a" -> "Descargo-arm32.apk"
        "x86_64" -> "Descargo-x86_64.apk"
        else -> "Descargo-arm64.apk"
    }

    /** "1.10" > "1.9": compares numerically part by part. */
    fun isNewer(remote: String, local: String): Boolean {
        val r = remote.trimStart('v', 'V').split('.').map { it.toIntOrNull() ?: 0 }
        val l = local.trimStart('v', 'V').split('.').map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(r.size, l.size)) {
            val a = r.getOrElse(i) { 0 }
            val b = l.getOrElse(i) { 0 }
            if (a != b) return a > b
        }
        return false
    }

    /** True if it's time for the daily automatic check. */
    fun dueForCheck(ctx: Context): Boolean {
        val p = ctx.getSharedPreferences("updater", Context.MODE_PRIVATE)
        return System.currentTimeMillis() - p.getLong("last", 0) > CHECK_EVERY_MS
    }

    /** Blocking. Newest release if it's newer than the installed app, else null. */
    fun check(ctx: Context): Release? {
        ctx.getSharedPreferences("updater", Context.MODE_PRIVATE).edit()
            .putLong("last", System.currentTimeMillis()).apply()
        val c = (URL("https://api.github.com/repos/$REPO/releases/latest").openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 15_000
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "Descargo-updater")
        }
        if (c.responseCode != 200) return null
        val j = JSONObject(c.inputStream.bufferedReader().use { it.readText() })
        val tag = j.optString("tag_name")
        if (!isNewer(tag, currentVersion(ctx))) return null
        val assets = j.optJSONArray("assets") ?: return null
        val want = assetName()
        val asset = (0 until assets.length()).map { assets.getJSONObject(it) }
            .firstOrNull { it.optString("name") == want } ?: return null
        return Release(
            version = tag.trimStart('v', 'V'),
            notes = j.optString("body").take(1500),
            apkUrl = asset.optString("browser_download_url"),
            sizeBytes = asset.optLong("size"),
        )
    }

    /** Blocking. Downloads the APK, reporting 0–100. Returns the file or null. */
    fun download(ctx: Context, r: Release, onProgress: (Int) -> Unit): File? = runCatching {
        val dir = File(ctx.cacheDir, "update").apply { deleteRecursively(); mkdirs() }
        val out = File(dir, "Descargo-${r.version}.apk")
        val c = (URL(r.apkUrl).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 30_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "Descargo-updater")
        }
        val total = if (c.contentLengthLong > 0) c.contentLengthLong else r.sizeBytes
        c.inputStream.use { input ->
            out.outputStream().use { o ->
                val buf = ByteArray(64 * 1024)
                var done = 0L
                var last = -1
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    o.write(buf, 0, n)
                    done += n
                    val pct = if (total > 0) (done * 100 / total).toInt() else 0
                    if (pct != last) { last = pct; onProgress(pct) }
                }
            }
        }
        out.takeIf { it.length() > 1_000_000 }
    }.getOrNull()

    /** Opens Android's installer. The user confirms; Android may first ask to allow this source. */
    fun install(ctx: Context, apk: File) {
        val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".files", apk)
        val i = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        ctx.startActivity(i)
    }
}
