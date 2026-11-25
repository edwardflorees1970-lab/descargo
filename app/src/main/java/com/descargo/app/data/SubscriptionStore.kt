package com.descargo.app.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** A channel/playlist whose new uploads are downloaded automatically with a fixed preset. */
data class Subscription(
    val id: String = UUID.randomUUID().toString(),
    val url: String,
    val title: String,
    val site: String,
    val presetLabel: String,
    /** Entry URLs already seen (downloaded or present when subscribing). */
    val seen: Set<String>,
    val lastCheck: Long = 0L,
    val lastError: String? = null,
    val enabled: Boolean = true,
)

object SubscriptionStore {
    private const val FILE = "subscriptions.json"
    private const val MAX_SEEN = 400

    private lateinit var file: File
    private val _items = MutableStateFlow<List<Subscription>>(emptyList())
    val items: StateFlow<List<Subscription>> = _items

    @Synchronized
    fun init(ctx: Context) {
        if (::file.isInitialized) return
        file = File(ctx.filesDir, FILE)
        _items.value = runCatching { parse(file.readText()) }.getOrDefault(emptyList())
    }

    @Synchronized
    fun upsert(sub: Subscription) {
        val trimmed = sub.copy(seen = sub.seen.toList().takeLast(MAX_SEEN).toSet())
        _items.value = _items.value.filterNot { it.id == sub.id || it.url == sub.url } + trimmed
        save()
    }

    @Synchronized
    fun remove(id: String) {
        _items.value = _items.value.filterNot { it.id == id }
        save()
    }

    @Synchronized
    fun reload(ctx: Context) {
        file = File(ctx.filesDir, FILE)
        _items.value = runCatching { parse(file.readText()) }.getOrDefault(emptyList())
    }

    fun byUrl(url: String) = _items.value.firstOrNull { it.url == url }

    private fun save() {
        val arr = JSONArray()
        _items.value.forEach { s ->
            arr.put(JSONObject().apply {
                put("id", s.id); put("url", s.url); put("title", s.title); put("site", s.site)
                put("presetLabel", s.presetLabel); put("seen", JSONArray(s.seen.toList()))
                put("lastCheck", s.lastCheck); put("lastError", s.lastError ?: JSONObject.NULL)
                put("enabled", s.enabled)
            })
        }
        runCatching {
            val tmp = File(file.parentFile, "$FILE.tmp")
            tmp.writeText(arr.toString())
            tmp.renameTo(file)
        }
    }

    private fun parse(text: String): List<Subscription> {
        val arr = JSONArray(text)
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            runCatching {
                val seen = o.optJSONArray("seen") ?: JSONArray()
                Subscription(
                    id = o.getString("id"),
                    url = o.getString("url"),
                    title = o.optString("title"),
                    site = o.optString("site"),
                    presetLabel = o.optString("presetLabel"),
                    seen = (0 until seen.length()).map { seen.getString(it) }.toSet(),
                    lastCheck = o.optLong("lastCheck"),
                    lastError = if (o.isNull("lastError")) null else o.optString("lastError"),
                    enabled = o.optBoolean("enabled", true),
                )
            }.getOrNull()
        }
    }
}
