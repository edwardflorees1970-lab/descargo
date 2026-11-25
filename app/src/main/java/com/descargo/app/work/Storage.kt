package com.descargo.app.work

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import androidx.documentfile.provider.DocumentFile
import android.webkit.MimeTypeMap
import com.descargo.app.data.AppSettings
import com.descargo.app.data.FolderMode
import com.descargo.app.engine.Kind
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import kotlin.coroutines.resume

/** Copies finished files into the public Download/Descargo folder. */
object Storage {
    const val FOLDER = "Descargo"

    data class Saved(val uri: Uri, val mime: String, val name: String)

    fun mimeOf(file: File): String = when (val ext = file.extension.lowercase()) {
        "flac" -> "audio/flac"
        "opus" -> "audio/ogg"
        "m4a" -> "audio/mp4"
        "mp3" -> "audio/mpeg"
        "mp4" -> "video/mp4"
        "webm" -> "video/webm"
        else -> MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
    }

    /** Deletes a file this app saved. Returns false if Android refuses (e.g. after reinstall). */
    fun delete(ctx: Context, uri: Uri): Boolean = try {
        if (DocumentsContract.isDocumentUri(ctx, uri)) DocumentsContract.deleteDocument(ctx.contentResolver, uri)
        else ctx.contentResolver.delete(uri, null, null) > 0
    } catch (_: SecurityException) {
        false
    } catch (_: IllegalArgumentException) {
        false
    } catch (_: java.io.FileNotFoundException) {
        false
    }

    // ---- User-chosen folder (Storage Access Framework) with "Audio" and "Video" subfolders ----

    const val AUDIO_DIR = "Audio"
    const val VIDEO_DIR = "Video"

    /** Root folder the user picked, or null if none / permission lost. */
    fun treeRoot(ctx: Context): DocumentFile? {
        val raw = AppSettings.current.treeUri.ifBlank { return null }
        val uri = Uri.parse(raw)
        val granted = ctx.contentResolver.persistedUriPermissions.any { it.uri == uri && it.isWritePermission }
        if (!granted) return null
        return DocumentFile.fromTreeUri(ctx, uri)?.takeIf { it.exists() && it.canWrite() }
    }

    /** "primary:Music/Mis descargas" → "Music/Mis descargas" (or "SD:…" for removable storage). */
    fun treeLabel(raw: String): String = runCatching {
        val id = DocumentsContract.getTreeDocumentId(Uri.parse(raw))
        val vol = id.substringBefore(':')
        val path = id.substringAfter(':', "")
        val prefix = if (vol == "primary") "" else "SD/"
        (prefix + path).ifBlank { if (vol == "primary") "Almacenamiento interno" else "Tarjeta SD" }
    }.getOrDefault(raw)

    private fun ensureDir(parent: DocumentFile, name: String): DocumentFile =
        parent.findFile(name)?.takeIf { it.isDirectory }
            ?: parent.createDirectory(name)
            ?: error("No se pudo crear la carpeta $name")

    /** Creates Audio/ and Video/ inside the chosen folder. */
    fun setupFolders(ctx: Context): Boolean = runCatching {
        val root = treeRoot(ctx) ?: return false
        ensureDir(root, AUDIO_DIR)
        ensureDir(root, VIDEO_DIR)
        true
    }.getOrDefault(false)

    fun subDirName(kind: Kind) = if (kind == Kind.VIDEO) VIDEO_DIR else AUDIO_DIR

    /** True if [uri] already lives inside the chosen folder. */
    fun isInTree(ctx: Context, uri: Uri): Boolean {
        val raw = AppSettings.current.treeUri.ifBlank { return false }
        if (!DocumentsContract.isDocumentUri(ctx, uri)) return false
        return runCatching {
            DocumentsContract.getDocumentId(uri).startsWith(DocumentsContract.getTreeDocumentId(Uri.parse(raw)))
        }.getOrDefault(false)
    }

    private fun safMime(name: String, mime: String): String {
        // Providers append an extension when it doesn't match the MIME type (e.g. ".opus" + audio/ogg).
        val ext = name.substringAfterLast('.', "").lowercase()
        return if (MimeTypeMap.getSingleton().getExtensionFromMimeType(mime) == ext) mime else "application/octet-stream"
    }

    private fun publishToTree(ctx: Context, root: DocumentFile, file: File, kind: Kind, site: String): Saved {
        val mime = mimeOf(file)
        var dir = ensureDir(root, subDirName(kind))
        if (AppSettings.current.bySite) dir = ensureDir(dir, site.replace(Regex("""[\\/:*?"<>|]"""), "").ifBlank { "Web" })
        var name = file.name
        var n = 1
        while (dir.findFile(name) != null) name = "${file.nameWithoutExtension} (${n++}).${file.extension}"
        val doc = dir.createFile(safMime(name, mime), name) ?: error("No se pudo crear el archivo en la carpeta elegida")
        try {
            ctx.contentResolver.openOutputStream(doc.uri)!!.use { out -> file.inputStream().use { it.copyTo(out, 1 shl 16) } }
        } catch (e: Exception) {
            doc.delete()
            throw e
        }
        return Saved(doc.uri, mime, doc.name ?: name)
    }

    /**
     * Moves an earlier download into the chosen folder's Audio/ or Video/. Returns the new location,
     * or null if it is already there or can't be read.
     */
    fun moveIntoTree(ctx: Context, uri: Uri, fileName: String, kind: Kind, site: String): Saved? {
        val root = treeRoot(ctx) ?: return null
        if (isInTree(ctx, uri)) return null
        val tmp = File(ctx.cacheDir, "move").apply { mkdirs() }.let { File(it, fileName) }
        try {
            ctx.contentResolver.openInputStream(uri)?.use { i -> tmp.outputStream().use { i.copyTo(it) } } ?: return null
            val saved = publishToTree(ctx, root, tmp, kind, site)
            delete(ctx, uri)
            return saved
        } catch (_: Exception) {
            return null
        } finally {
            tmp.delete()
        }
    }

    /** Public base folder + subfolder for a download, following the user's folder settings. */
    fun target(kind: Kind, site: String): Pair<String, String> {
        val s = AppSettings.current
        val base = when {
            s.folderMode == FolderMode.MEDIA && kind == Kind.VIDEO -> Environment.DIRECTORY_MOVIES
            s.folderMode == FolderMode.MEDIA -> Environment.DIRECTORY_MUSIC
            else -> Environment.DIRECTORY_DOWNLOADS
        }
        val name = s.folderName.ifBlank { FOLDER }
        val sub = if (s.bySite) name + "/" + site.replace(Regex("""[\\/:*?"<>|]"""), "").ifBlank { "Web" } else name
        return base to sub
    }

    /** Human-readable destination, e.g. "Music/Descargo" or "Mi carpeta/Audio". */
    fun describeTarget(kind: Kind = Kind.MP3): String {
        val tree = AppSettings.current.treeUri
        if (tree.isNotBlank()) return treeLabel(tree) + "/" + subDirName(kind) + if (AppSettings.current.bySite) "/<sitio>" else ""
        return describeDefault(kind)
    }

    private fun describeDefault(kind: Kind): String = target(kind, "Sitio").let { (b, s) ->
        "$b/" + if (AppSettings.current.bySite) s.substringBefore('/') + "/<sitio>" else s
    }

    suspend fun publish(ctx: Context, file: File, kind: Kind, site: String): Saved {
        treeRoot(ctx)?.let { root -> return publishToTree(ctx, root, file, kind, site) }
        val mime = mimeOf(file)
        val (base, sub) = target(kind, site)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val resolver = ctx.contentResolver
            val collection = when (base) {
                Environment.DIRECTORY_MUSIC -> MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
                Environment.DIRECTORY_MOVIES -> MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                else -> MediaStore.Downloads.EXTERNAL_CONTENT_URI
            }
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, file.name)
                put(MediaStore.MediaColumns.MIME_TYPE, mime)
                put(MediaStore.MediaColumns.RELATIVE_PATH, "$base/$sub")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val uri = resolver.insert(collection, values)
                ?: error("MediaStore rechazó el archivo")
            try {
                resolver.openOutputStream(uri)!!.use { out -> file.inputStream().use { it.copyTo(out, 1 shl 16) } }
                resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            } catch (e: Exception) {
                resolver.delete(uri, null, null)
                throw e
            }
            Saved(uri, mime, file.name)
        } else {
            @Suppress("DEPRECATION")
            val dir = File(Environment.getExternalStoragePublicDirectory(base), sub)
            dir.mkdirs()
            var target = File(dir, file.name)
            var n = 1
            while (target.exists()) target = File(dir, "${file.nameWithoutExtension} ($n).${file.extension}").also { n++ }
            file.copyTo(target)
            val uri = suspendCancellableCoroutine { cont ->
                MediaScannerConnection.scanFile(ctx, arrayOf(target.absolutePath), arrayOf(mime)) { _, u ->
                    cont.resume(u ?: Uri.fromFile(target))
                }
            }
            Saved(uri, mime, target.name)
        }
    }
}
