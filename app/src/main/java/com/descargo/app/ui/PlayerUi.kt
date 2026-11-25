package com.descargo.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.ui.PlayerView
import com.descargo.app.player.PlayerController

/** Compact bar above the navigation while something is loaded in the player. */
@Composable
fun MiniPlayer(p: PlayerController, onExpand: () -> Unit) {
    if (!p.hasMedia) return
    Row(
        Modifier.fillMaxWidth().background(Selected).clickable(onClick = onExpand)
            .padding(start = 12.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Thumb(p.artwork, Modifier.size(width = 56.dp, height = 40.dp))
        Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
            Text(p.title ?: "Reproduciendo", maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium)
            p.subtitle?.let { Text(it, maxLines = 1, style = MaterialTheme.typography.bodySmall, color = Muted) }
        }
        TextButton(onClick = { p.toggle() }) { Text(if (p.isPlaying) "⏸" else "▶", fontSize = 20.sp) }
        TextButton(onClick = { p.next() }) { Text("⏭", fontSize = 20.sp) }
        TextButton(onClick = { p.stop() }) { Text("✕", color = Muted) }
    }
}

/** Full-screen player: video surface (or cover for audio) with Media3's standard controls. */
@Composable
fun FullPlayer(p: PlayerController, onClose: () -> Unit) {
    BackHandler(onBack = onClose)
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    useController = true
                    controllerShowTimeoutMs = 3500
                    setShowNextButton(true)
                    setShowPreviousButton(true)
                }
            },
            update = { it.player = p.controller },
            onRelease = { it.player = null },
            modifier = Modifier.fillMaxSize(),
        )
        Row(Modifier.fillMaxWidth().safeDrawingPadding().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onClose) { Text("⌄ Cerrar", color = Color.White) }
            Text(
                p.title ?: "", color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).padding(end = 12.dp),
            )
        }
    }
}
