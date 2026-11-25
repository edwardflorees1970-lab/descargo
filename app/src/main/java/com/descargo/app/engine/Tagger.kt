package com.descargo.app.engine

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import java.io.File
import java.util.concurrent.TimeUnit

data class Tags(val title: String, val artist: String, val album: String)

/**
 * Rewrites audio tags (and optionally the cover) of a saved file using the ffmpeg binary that
 * youtubedl-android already ships, with a lossless stream copy.
 */
object Tagger {
    private val COVER_OK = setOf("mp3", "flac", "m4a", "mp4")

    fun supportsCover(fileName: String) = fileName.substringAfterLast('.').lowercase() in COVER_OK

    fun read(ctx: Context, uri: Uri): Tags = runCatching {
        // Not AutoCloseable before API 29, so release manually.
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(ctx, uri)
            Tags(
                r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE).orEmpty(),
                r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST).orEmpty(),
                r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM).orEmpty(),
            )
        } finally {
            r.release()
        }
    }.getOrDefault(Tags("", "", ""))

    /** Blocking. Returns null on success or an error message. */
    fun write(ctx: Context, uri: Uri, fileName: String, tags: Tags, cover: Uri?): String? {
        val ext = fileName.substringAfterLast('.', "mp3").lowercase()
        val work = File(ctx.cacheDir, "tag").apply { deleteRecursively(); mkdirs() }
        try {
            val input = File(work, "in.$ext")
            ctx.contentResolver.openInputStream(uri)?.use { i -> input.outputStream().use { i.copyTo(it) } }
                ?: return "No se pudo leer el archivo"
            val coverFile = cover?.let { c ->
                File(work, "cover.jpg").also { f ->
                    ctx.contentResolver.openInputStream(c)?.use { i -> f.outputStream().use { i.copyTo(it) } }
                }
            }?.takeIf { it.length() > 0 && supportsCover(fileName) }
            val output = File(work, "out.$ext")

            val cmd = mutableListOf(ffmpegPath(ctx), "-y", "-i", input.absolutePath)
            if (coverFile != null) {
                cmd += listOf("-i", coverFile.absolutePath, "-map", "0:a", "-map", "1:0", "-c", "copy",
                    "-disposition:v:0", "attached_pic")
                if (ext == "mp3") cmd += listOf("-id3v2_version", "3", "-metadata:s:v", "title=Album cover")
            } else {
                cmd += listOf("-map", "0", "-c", "copy")
            }
            cmd += listOf(
                "-metadata", "title=${tags.title}",
                "-metadata", "artist=${tags.artist}",
                "-metadata", "album=${tags.album}",
                output.absolutePath,
            )
            val p = ProcessBuilder(cmd).redirectErrorStream(true).apply {
                environment()["LD_LIBRARY_PATH"] = libPath(ctx)
            }.start()
            val log = p.inputStream.bufferedReader().readText()
            if (!p.waitFor(120, TimeUnit.SECONDS)) {
                p.destroy()
                return "ffmpeg tardó demasiado"
            }
            if (p.exitValue() != 0 || !output.exists() || output.length() == 0L) {
                return "ffmpeg falló: " + log.lineSequence().lastOrNull { it.isNotBlank() }.orEmpty().take(160)
            }
            ctx.contentResolver.openOutputStream(uri, "wt")?.use { o -> output.inputStream().use { it.copyTo(o) } }
                ?: return "No se pudo escribir el archivo"
            return null
        } catch (e: Exception) {
            return e.message ?: e.toString()
        } finally {
            work.deleteRecursively()
        }
    }

    private fun ffmpegPath(ctx: Context) = File(ctx.applicationInfo.nativeLibraryDir, "libffmpeg.so").absolutePath

    private fun libPath(ctx: Context): String {
        val base = File(ctx.noBackupFilesDir, "youtubedl-android/packages")
        return listOf("ffmpeg", "python").joinToString(":") { File(base, "$it/usr/lib").absolutePath }
    }
}
