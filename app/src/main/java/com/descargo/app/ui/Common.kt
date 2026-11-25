package com.descargo.app.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.LruCache
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.ui.platform.LocalContext
import android.os.Build
import com.descargo.app.data.ThemeMode
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.text.DateFormat
import java.util.Date
import java.util.Locale

val Accent = Color(0xFFE5484D)
val Warn = Color(0xFFE5A84D)

// Theme-aware colors (light/dark/Material You) used across screens.
val Primary: Color @Composable get() = MaterialTheme.colorScheme.primary
val Muted: Color @Composable get() = MaterialTheme.colorScheme.onSurfaceVariant
val CardBg: Color @Composable get() = MaterialTheme.colorScheme.surfaceContainer
val Divider: Color @Composable get() = MaterialTheme.colorScheme.outlineVariant
val Selected: Color @Composable get() = MaterialTheme.colorScheme.secondaryContainer

private val DarkColors = darkColorScheme(
    primary = Accent,
    onPrimary = Color.White,
    secondaryContainer = Color(0xFF3A2224),
    onSecondaryContainer = Color.White,
    background = Color(0xFF111316),
    surface = Color(0xFF111316),
    onSurfaceVariant = Color(0xFF9AA0A6),
    surfaceVariant = Color(0xFF1C1F24),
    surfaceContainer = Color(0xFF1C1F24),
    surfaceContainerHigh = Color(0xFF202328),
    surfaceContainerHighest = Color(0xFF23272D),
    outlineVariant = Color(0xFF2A2E35),
)

private val LightColors = lightColorScheme(
    primary = Color(0xFFC8303A),
    onPrimary = Color.White,
    secondaryContainer = Color(0xFFFFDAD8),
    onSecondaryContainer = Color(0xFF410006),
    background = Color(0xFFFBF8F8),
    surface = Color(0xFFFBF8F8),
    onSurfaceVariant = Color(0xFF5F6368),
    surfaceVariant = Color(0xFFF0ECEC),
    surfaceContainer = Color(0xFFF2EEEE),
    surfaceContainerHigh = Color(0xFFECE8E8),
    surfaceContainerHighest = Color(0xFFE6E1E1),
    outlineVariant = Color(0xFFE0DADA),
)

@Composable
fun DescargoTheme(mode: ThemeMode = ThemeMode.DARK, dynamic: Boolean = false, content: @Composable () -> Unit) {
    val dark = when (mode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.DARK -> true
        ThemeMode.LIGHT -> false
    }
    val ctx = LocalContext.current
    val scheme = when {
        dynamic && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        dark -> DarkColors
        else -> LightColors
    }
    MaterialTheme(colorScheme = scheme, content = content)
}

/** Thumbnails are downsampled to [MAX_W] px wide and kept in a small in-memory LRU. */
private object ImageCache {
    const val MAX_W = 480
    val cache = object : LruCache<String, ImageBitmap>(24 * 1024 * 1024) {
        override fun sizeOf(key: String, value: ImageBitmap) = value.width * value.height * 4
    }

    fun load(url: String): ImageBitmap? {
        cache.get(url)?.let { return it }
        val bytes = runCatching {
            (URL(url).openConnection() as HttpURLConnection).run {
                connectTimeout = 10_000
                readTimeout = 15_000
                setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) Descargo")
                inputStream.use { it.readBytes() }
            }
        }.getOrNull() ?: return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= MAX_W) sample *= 2
        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: return null
        return bmp.asImageBitmap().also { cache.put(url, it) }
    }
}

@Composable
fun Thumb(url: String?, modifier: Modifier, fallback: String = "") {
    val img by produceState<ImageBitmap?>(null, url) {
        value = url?.let { withContext(Dispatchers.IO) { ImageCache.load(it) } }
    }
    Box(modifier.clip(RoundedCornerShape(8.dp)).background(Color.Black), contentAlignment = Alignment.Center) {
        val i = img
        if (i != null) Image(i, null, contentScale = ContentScale.Crop, modifier = Modifier.matchParentSize())
        else if (fallback.isNotEmpty()) Text(fallback, color = Muted, fontSize = 12.sp)
    }
}

fun openFile(ctx: Context, uri: Uri, mime: String) {
    val i = Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    try {
        ctx.startActivity(i)
    } catch (_: ActivityNotFoundException) {
        Toast.makeText(ctx, "No hay app para abrir este archivo", Toast.LENGTH_SHORT).show()
    } catch (_: SecurityException) {
        Toast.makeText(ctx, "El archivo ya no existe", Toast.LENGTH_SHORT).show()
    }
}

fun shareFile(ctx: Context, uri: Uri, mime: String, title: String) {
    val send = Intent(Intent.ACTION_SEND).setType(mime).putExtra(Intent.EXTRA_STREAM, uri)
        .putExtra(Intent.EXTRA_TITLE, title).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    ctx.startActivity(Intent.createChooser(send, "Compartir"))
}

fun formatDuration(sec: Double): String {
    val s = sec.toLong()
    return if (s >= 3600) String.format(Locale.US, "%d:%02d:%02d", s / 3600, s % 3600 / 60, s % 60)
    else String.format(Locale.US, "%d:%02d", s / 60, s % 60)
}

fun formatSize(b: Long): String = when {
    b >= 1L shl 30 -> String.format(Locale.US, "%.2f GB", b / (1024.0 * 1024 * 1024))
    b >= 1L shl 20 -> String.format(Locale.US, "%.1f MB", b / (1024.0 * 1024))
    else -> String.format(Locale.US, "%d KB", b / 1024)
}

fun formatDate(ms: Long): String =
    DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(ms))
