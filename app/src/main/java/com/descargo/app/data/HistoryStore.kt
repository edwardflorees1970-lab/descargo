package com.descargo.app.data

import android.content.Context
import com.descargo.app.engine.Kind
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors

enum class Status { QUEUED, RUNNING, PAUSED, DONE, FAILED, CANCELLED }

data class HistoryItem(
    val id: String,
    val url: String,
    val title: String,
    val thumbnail: String?,
    val site: String,
    val kind: Kind,
    val optionLabel: String,
    val args: List<String>,
    val status: Status = Status.QUEUED,
    val createdAt: Long = System.currentTimeMillis(),
    val uri: String? = null,
    val mime: String? = null,
    val fileName: String? = null,
    val sizeBytes: Long? = null,
    val error: String? = null,
) {
    val isActive get() = status == Status.QUEUED || status == Status.RUNNING

    /** Shown in "En curso": active or paused. */
    val isPending get() = isActive || status == Status.PAUSED
}

/** Small JSON-file store: survives app restarts, unlike WorkManager's pruned job list. */
object HistoryStore {
    private const val FILE = "history.json"
    private const val MAX_ITEMS = 2000

    private lateinit var file: File
    private val io = Executors.newSingleThreadExecutor()
    private val _items = MutableStateFlow<List<HistoryItem>>(emptyList())
    val items: StateFlow<List<HistoryItem>> = _items

    @Synchronized
    fun init(ctx: Context) {
        if (::file.isInitialized) return
        file = File(ctx.filesDir, FILE)
        val loaded = runCatching { parse(file.readText()) }.getOrDefault(emptyList())
        // Anything "running" when the process died is re-run by WorkManager; show it as queued.
        _items.value = loaded.map { if (it.status == Status.RUNNING) it.copy(status = Status.QUEUED) else it }
    }

    fun get(id: String): HistoryItem? = _items.value.firstOrNull { it.id == id }

    @Synchronized
    fun add(items: List<HistoryItem>) {
        _items.value = (items + _items.value).take(MAX_ITEMS)
        save()
    }

    @Synchronized
    fun update(id: String, transform: (HistoryItem) -> HistoryItem) {
        _items.value = _items.value.map { if (it.id == id) transform(it) else it }
        save()
    }

    @Synchronized
    fun remove(ids: Collection<String>) {
        val set = ids.toSet()
        _items.value = _items.value.filterNot { it.id in set }
        save()
    }

    private fun save() {
        val snapshot = _items.value
        io.execute {
            runCatching {
                val tmp = File(file.parentFile, "$FILE.tmp")
                tmp.writeText(serialize(snapshot))
                tmp.renameTo(file)
            }
        }
    }

    private fun serialize(list: List<HistoryItem>): String {
        val arr = JSONArray()
        list.forEach { i ->
            arr.put(JSONObject().apply {
                put("id", i.id); put("url", i.url); put("title", i.title)
                put("thumbnail", i.thumbnail ?: JSONObject.NULL); put("site", i.site)
                put("kind", i.kind.name); put("optionLabel", i.optionLabel)
                put("args", JSONArray(i.args)); put("status", i.status.name)
                put("createdAt", i.createdAt); put("uri", i.uri ?: JSONObject.NULL)
                put("mime", i.mime ?: JSONObject.NULL); put("fileName", i.fileName ?: JSONObject.NULL)
                put("sizeBytes", i.sizeBytes ?: JSONObject.NULL); put("error", i.error ?: JSONObject.NULL)
            })
        }
        return arr.toString()
    }

    private fun parse(text: String): List<HistoryItem> {
        val arr = JSONArray(text)
        return (0 until arr.length()).mapNotNull { idx ->
            val o = arr.optJSONObject(idx) ?: return@mapNotNull null
            runCatching {
                val a = o.optJSONArray("args") ?: JSONArray()
                HistoryItem(
                    id = o.getString("id"),
                    url = o.getString("url"),
                    title = o.optString("title"),
                    thumbnail = o.str("thumbnail"),
                    site = o.optString("site"),
                    kind = Kind.valueOf(o.getString("kind")),
                    optionLabel = o.optString("optionLabel"),
                    args = (0 until a.length()).map { a.getString(it) },
                    status = Status.valueOf(o.getString("status")),
                    createdAt = o.optLong("createdAt"),
                    uri = o.str("uri"),
                    mime = o.str("mime"),
                    fileName = o.str("fileName"),
                    sizeBytes = if (o.isNull("sizeBytes")) null else o.optLong("sizeBytes"),
                    error = o.str("error"),
                )
            }.getOrNull()
        }
    }

    private fun JSONObject.str(k: String): String? =
        if (!has(k) || isNull(k)) null else optString(k).ifBlank { null }
}
