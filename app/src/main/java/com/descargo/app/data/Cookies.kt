package com.descargo.app.data

import android.content.Context
import android.webkit.CookieManager
import java.io.File

/**
 * Exports the in-app browser's cookies (where the user may be logged in) to a Netscape
 * cookies.txt that yt-dlp reads with --cookies, so it can fetch private or age-restricted media.
 * The file lives in app-private storage and is rewritten before each run.
 */
object Cookies {
    private val DOMAINS = listOf(
        "youtube.com", "google.com", "tiktok.com", "facebook.com", "instagram.com",
        "pinterest.com", "x.com", "twitter.com", "vimeo.com", "reddit.com",
    )

    fun file(ctx: Context) = File(ctx.filesDir, "cookies.txt")

    /** Returns the cookie file, or null when there is no session to share. */
    fun export(ctx: Context): File? {
        val cm = runCatching { CookieManager.getInstance() }.getOrNull() ?: return null
        val expiry = System.currentTimeMillis() / 1000 + 30L * 24 * 3600
        val sb = StringBuilder("# Netscape HTTP Cookie File\n")
        var count = 0
        for (d in DOMAINS) {
            val raw = cm.getCookie("https://www.$d") ?: continue
            for (pair in raw.split(';')) {
                val i = pair.indexOf('=')
                if (i <= 0) continue
                val name = pair.substring(0, i).trim()
                val value = pair.substring(i + 1).trim()
                if (name.isEmpty()) continue
                sb.append(".").append(d).append("\tTRUE\t/\tTRUE\t").append(expiry)
                    .append('\t').append(name).append('\t').append(value).append('\n')
                count++
            }
        }
        if (count == 0) return null
        return file(ctx).apply { writeText(sb.toString()) }
    }

    /** Cookie names that only exist after a real login (visitor cookies don't count). */
    private val AUTH = listOf(
        "YouTube" to ("youtube.com" to listOf("SAPISID", "__Secure-3PSID", "LOGIN_INFO")),
        "Facebook" to ("facebook.com" to listOf("c_user")),
        "Instagram" to ("instagram.com" to listOf("sessionid", "ds_user_id")),
        "TikTok" to ("tiktok.com" to listOf("sessionid", "sid_tt")),
        "X" to ("x.com" to listOf("auth_token")),
        "Pinterest" to ("pinterest.com" to listOf("_pinterest_sess")),
    )

    /** Sites where the in-app browser holds a logged-in session. */
    fun loggedInSites(): List<String> = runCatching {
        val cm = CookieManager.getInstance()
        AUTH.filter { (_, v) ->
            val raw = cm.getCookie("https://www.${v.first}") ?: return@filter false
            val names = raw.split(';').map { it.substringBefore('=').trim() }.toSet()
            v.second.any { it in names }
        }.map { it.first }
    }.getOrDefault(emptyList())

    fun clear(ctx: Context) {
        runCatching {
            CookieManager.getInstance().removeAllCookies(null)
            CookieManager.getInstance().flush()
        }
        file(ctx).delete()
    }
}
