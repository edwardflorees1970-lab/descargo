package com.descargo.app.data

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Exports / imports settings, library history and subscriptions as one JSON file. */
object Backup {
    private const val VERSION = 1

    fun export(ctx: Context, dest: Uri): String? = runCatching {
        val settings = JSONObject()
        AppSettings.exportMap().forEach { (k, v) -> if (k != "treeUri" && v != null) settings.put(k, v) }
        val root = JSONObject()
            .put("app", "Descargo")
            .put("version", VERSION)
            .put("createdAt", System.currentTimeMillis())
            .put("settings", settings)
            .put("history", readArray(File(ctx.filesDir, "history.json")))
            .put("subscriptions", readArray(File(ctx.filesDir, "subscriptions.json")))
        ctx.contentResolver.openOutputStream(dest, "wt")!!.use { it.write(root.toString(2).toByteArray()) }
        null
    }.getOrElse { it.message ?: it.toString() }

    /** Returns null on success or an error message. Files already on the phone are not touched. */
    fun import(ctx: Context, src: Uri): String? = runCatching {
        val text = ctx.contentResolver.openInputStream(src)!!.use { it.readBytes().decodeToString() }
        val root = JSONObject(text)
        require(root.optString("app") == "Descargo") { "No es un respaldo de Descargo" }
        root.optJSONObject("settings")?.let { s ->
            AppSettings.importMap(ctx, s.keys().asSequence().associateWith { s.get(it) })
        }
        root.optJSONArray("history")?.let { File(ctx.filesDir, "history.json").writeText(it.toString()) }
        root.optJSONArray("subscriptions")?.let { File(ctx.filesDir, "subscriptions.json").writeText(it.toString()) }
        HistoryStore.reload(ctx)
        SubscriptionStore.reload(ctx)
        null
    }.getOrElse { it.message ?: it.toString() }

    private fun readArray(f: File): JSONArray = runCatching { JSONArray(f.readText()) }.getOrDefault(JSONArray())
}
