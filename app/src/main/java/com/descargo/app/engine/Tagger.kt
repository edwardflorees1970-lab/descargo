package com.descargo.app.engine

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import java.io.File
import java.util.concurrent.TimeUnit

data class Tags(val title: String, val artist: String, val album: String, val year: String = "")

/**
 * Tag editing and format conversion using the ffmpeg binary youtubedl-android already ships.
 * Tag writes are lossless stream copies.
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
                r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_YEAR).orEmpty(),
            )
        } finally {
            r.release()
        }
    }.getOrDefault(Tags("", "", ""))

    /** Blocking. Returns null on success or an error message. */
    fun write(
        ctx: Context,
        uri: Uri,
        fileName: String,
        tags: Tags,
        cover: Uri?,
        coverFile: File? = null,
        lyrics: String? = null,
    ): String? {
        val ext = fileName.substringAfterLast('.', "mp3").lowercase()
        val work = File(ctx.cacheDir, "tag-${System.nanoTime()}").apply { mkdirs() }
        try {
            val input = File(work, "in.$ext")
            ctx.contentResolver.openInputStream(uri)?.use { i -> input.outputStream().use { i.copyTo(it) } }
                ?: return "No se pudo leer el archivo"
            val picked = cover?.let { c ->
                File(work, "cover.jpg").also { f ->
                    ctx.contentResolver.openInputStream(c)?.use { i -> f.outputStream().use { i.copyTo(it) } }
                }
            } ?: coverFile
            val coverToUse = picked?.takeIf { it.exists() && it.length() > 0 && supportsCover(fileName) }
            val output = File(work, "out.$ext")

            val cmd = mutableListOf(ffmpegPath(ctx), "-y", "-i", input.absolutePath)
            if (coverToUse != null) {
                // Keep audio (+ chapters/metadata), replace any old picture with the new one.
                cmd += listOf("-i", coverToUse.absolutePath, "-map", "0:a", "-map", "1:0", "-c", "copy",
                    "-disposition:v:0", "attached_pic")
                if (ext == "mp3") cmd += listOf("-id3v2_version", "3", "-metadata:s:v", "title=Album cover")
            } else {
                cmd += listOf("-map", "0", "-c", "copy")
                if (ext == "mp3") cmd += listOf("-id3v2_version", "3")
            }
            cmd += listOf("-map_chapters", "0")
            cmd += listOf("-metadata", "title=${tags.title}", "-metadata", "artist=${tags.artist}", "-metadata", "album=${tags.album}")
            if (tags.year.isNotBlank()) cmd += listOf("-metadata", "date=${tags.year}")
            if (!lyrics.isNullOrBlank()) cmd += listOf("-metadata", "lyrics=$lyrics")
            cmd += output.absolutePath

            run(ctx, cmd, output)?.let { return it }
            ctx.contentResolver.openOutputStream(uri, "wt")?.use { o -> output.inputStream().use { it.copyTo(o) } }
                ?: return "No se pudo escribir el archivo"
            return null
        } catch (e: Exception) {
            return e.message ?: e.toString()
        } finally {
            work.deleteRecursively()
        }
    }

    /**
     * Blocking. Converts a saved file to MP3 at [kbps] (also extracts audio from videos).
     * Writes the result to [output]; returns null on success or an error message.
     */
    fun toMp3(ctx: Context, uri: Uri, fileName: String, kbps: Int, output: File): String? {
        val ext = fileName.substringAfterLast('.', "bin").lowercase()
        val work = File(ctx.cacheDir, "conv-${System.nanoTime()}").apply { mkdirs() }
        try {
            val input = File(work, "in.$ext")
            ctx.contentResolver.openInputStream(uri)?.use { i -> input.outputStream().use { i.copyTo(it) } }
                ?: return "No se pudo leer el archivo"
            val cmd = listOf(
                ffmpegPath(ctx), "-y", "-i", input.absolutePath,
                "-map", "0:a:0", "-vn", "-c:a", "libmp3lame", "-b:a", "${kbps}k",
                "-map_metadata", "0", "-map_chapters", "0", "-id3v2_version", "3",
                output.absolutePath,
            )
            return run(ctx, cmd, output, timeoutSec = 1800)
        } catch (e: Exception) {
            return e.message ?: e.toString()
        } finally {
            work.deleteRecursively()
        }
    }

    private fun run(ctx: Context, cmd: List<String>, output: File, timeoutSec: Long = 180): String? {
        val p = ProcessBuilder(cmd).redirectErrorStream(true).apply {
            environment()["LD_LIBRARY_PATH"] = libPath(ctx)
        }.start()
        val log = StringBuilder()
        val reader = Thread { p.inputStream.bufferedReader().forEachLine { if (log.length < 8000) log.appendLine(it) } }
        reader.start()
        if (!p.waitFor(timeoutSec, TimeUnit.SECONDS)) {
            p.destroy()
            return "ffmpeg tardó demasiado"
        }
        reader.join(2000)
        if (p.exitValue() != 0 || !output.exists() || output.length() == 0L) {
            return "ffmpeg falló: " + log.lineSequence().lastOrNull { it.isNotBlank() }.orEmpty().take(160)
        }
        return null
    }

    private fun ffmpegPath(ctx: Context) = File(ctx.applicationInfo.nativeLibraryDir, "libffmpeg.so").absolutePath

    private fun libPath(ctx: Context): String {
        val base = File(ctx.noBackupFilesDir, "youtubedl-android/packages")
        return listOf("ffmpeg", "python").joinToString(":") { File(base, "$it/usr/lib").absolutePath }
    }
}
