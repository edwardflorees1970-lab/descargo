package com.descargo.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.Dp
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ripple
import androidx.compose.runtime.remember
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.descargo.app.data.HistoryItem
import com.descargo.app.data.Status
import com.descargo.app.engine.DownloadOption
import com.descargo.app.engine.EngineState
import com.descargo.app.engine.Kind
import com.descargo.app.engine.MediaInfo
import com.descargo.app.engine.PlaylistInfo
import com.descargo.app.engine.Presets

private const val MAX_ACTIVE_SHOWN = 5

@Composable
fun HomeScreen(vm: MainViewModel) {
    val engine by vm.engineState.collectAsState()
    val history by vm.history.collectAsStateWithLifecycle()
    val progress by vm.progress.collectAsStateWithLifecycle(initialValue = emptyMap())
    val active = history.filter { it.isPending }
    val anyRunning = active.any { it.isActive }
    val anyPaused = active.any { it.status == Status.PAUSED }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(bottom = 24.dp),
    ) {
        item { Header(engine) }
        vm.clipboardLink?.let { link -> item { ClipboardCard(link, onUse = { vm.analyzeLink(link) }, onDismiss = { vm.clipboardLink = null }) } }
        item { UrlBox(vm) }

        // Active downloads sit above results so they stay visible even under a long playlist.
        if (active.isNotEmpty()) {
            item {
                Row(
                    Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("En curso (${active.size})", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    if (anyRunning) TextButton(onClick = vm::pauseAll) { Text("Pausar todo") }
                    if (anyPaused) TextButton(onClick = vm::resumeAll) { Text("Reanudar todo") }
                    if (active.size > 1) TextButton(onClick = vm::cancelAll) { Text("Cancelar", color = Muted) }
                }
            }
            val shown = active.sortedBy { it.status.ordinal }.take(MAX_ACTIVE_SHOWN)
            items(shown, key = { it.id }) { item ->
                Box(Modifier.animateItem()) {
                    ActiveRow(
                        item, progress[item.id],
                        onCancel = { vm.cancel(item.id) },
                        onPause = { vm.pause(item.id) },
                        onResume = { vm.resume(item.id) },
                    )
                }
            }
            if (active.size > shown.size) item {
                Note("…y ${active.size - shown.size} más en cola")
            }
        }

        when (val s = vm.state) {
            UiState.Idle -> item { Hint() }
            UiState.Loading -> item {
                LoadingCard()
            }
            is UiState.Error -> item {
                Text(s.message, color = Primary, modifier = Modifier.padding(horizontal = 16.dp))
            }
            is UiState.Loaded -> {
                item { MediaHeader(s.info) }
                if (s.inPlaylist) item {
                    OutlinedButton(
                        onClick = { vm.analyze(forcePlaylist = true) },
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    ) { Text("Este video es parte de una lista · Ver lista completa") }
                }
                item { KindTabs(vm.tab, vm::selectTab) }
                s.info.spoken?.let { sp ->
                    if (vm.tab != Kind.VIDEO) item {
                        Note(
                            "🎧 ${sp.genre} detectado (${formatDuration(s.info.durationSec ?: 0.0)}): se guardan " +
                                (if (s.info.chapters.isNotEmpty()) "${s.info.chapters.size} capítulos, " else "los capítulos, ") +
                                "el resumen y el autor dentro del archivo, más un .txt con el índice."
                        )
                    }
                }
                if (vm.tab == Kind.FLAC) item { Note(flacNote(s.info)) }
                if (vm.tab == Kind.ORIGINAL) item {
                    Note("La pista tal cual la entrega el sitio: máxima calidad real, sin recodificar.")
                }
                items(s.info.options[vm.tab].orEmpty(), key = { it.label + it.args.hashCode() }) { opt ->
                    Box(Modifier.animateItem()) { OptionRow(opt, selected = vm.selected == opt) { vm.selected = opt } }
                }
                if (s.info.durationSec != null) item { ClipBox(vm) }
                item { PrimaryButton(if (vm.clipOn) "Descargar fragmento" else "Descargar", enabled = vm.selected != null, onClick = vm::download) }
            }
            is UiState.PlaylistLoaded -> playlist(vm, s.info)
            is UiState.ImageLoaded -> {
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                        colors = CardDefaults.cardColors(containerColor = CardBg),
                    ) {
                        Thumb(s.image.imageUrl, Modifier.fillMaxWidth().aspectRatio(1f))
                        Column(Modifier.padding(12.dp)) {
                            Text(s.image.title, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text("Imagen · ${s.image.site}", style = MaterialTheme.typography.bodySmall, color = Muted)
                        }
                    }
                }
                item { Note("Esta publicación es una foto. Se guarda en la carpeta «Imagenes» (solo la imagen principal; los carruseles no se pueden).") }
                item { PrimaryButton("Descargar imagen", onClick = vm::downloadImage) }
            }
        }

        val recent = history.firstOrNull { it.status == Status.DONE }
        if (recent != null && vm.state !is UiState.PlaylistLoaded) item {
            TextButton(onClick = { vm.screen = Screen.LIBRARY }, modifier = Modifier.padding(horizontal = 8.dp)) {
                val n = history.count { it.status == Status.DONE }
                Text("Ver biblioteca ($n ${if (n == 1) "archivo" else "archivos"})")
            }
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.playlist(vm: MainViewModel, pl: PlaylistInfo) {
    item {
        Card(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            colors = CardDefaults.cardColors(containerColor = CardBg),
        ) {
            Column(Modifier.padding(12.dp)) {
                Text(pl.title, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                val meta = listOfNotNull(pl.uploader, "${pl.entries.size} elementos", pl.site)
                Text(meta.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = Muted)
            }
        }
    }
    item {
        Text("Formato para todos", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(horizontal = 16.dp))
        Spacer(Modifier.height(6.dp))
        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(Presets.all) { p ->
                FilterChip(
                    selected = vm.preset == p,
                    onClick = { vm.preset = p },
                    label = { Text(p.label) },
                    colors = FilterChipDefaults.filterChipColors(selectedContainerColor = Selected),
                )
            }
        }
    }
    item {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                "${vm.checked.size} de ${pl.entries.size} seleccionados",
                style = MaterialTheme.typography.bodySmall, color = Muted,
                modifier = Modifier.weight(1f).padding(start = 8.dp),
            )
            TextButton(onClick = { vm.checkAll(true) }) { Text("Todos") }
            TextButton(onClick = { vm.checkAll(false) }) { Text("Ninguno") }
        }
    }
    item {
        PrimaryButton(
            "Descargar ${vm.checked.size}",
            enabled = vm.checked.isNotEmpty(),
            onClick = vm::downloadPlaylist,
        )
    }
    item {
        val subs by vm.subscriptions.collectAsStateWithLifecycle()
        val existing = subs.firstOrNull { it.url == pl.url }
        Column(Modifier.padding(horizontal = 16.dp)) {
            if (existing == null) {
                OutlinedButton(onClick = vm::subscribe, modifier = Modifier.fillMaxWidth()) {
                    Text("Suscribirse · bajar los videos nuevos como «${vm.preset.label}»")
                }
                Text(
                    "Revisa cada ${vm.settings.collectAsStateWithLifecycle().value.subsIntervalH} h. Los videos actuales no se bajan.",
                    style = MaterialTheme.typography.bodySmall, color = Muted,
                )
            } else {
                Text("✓ Suscrito (${existing.presetLabel}). Gestiónalo en Ajustes.", color = Muted, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
    itemsIndexed(pl.entries, key = { i, e -> "$i:${e.url}" }) { i, e ->
        Row(
            Modifier.fillMaxWidth().clickable { vm.toggle(i) }.padding(horizontal = 8.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(checked = i in vm.checked, onCheckedChange = { vm.toggle(i) })
            Thumb(e.thumbnail, Modifier.width(96.dp).aspectRatio(16f / 9f))
            Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
                Text(e.title, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
                e.durationSec?.let { Text(formatDuration(it), style = MaterialTheme.typography.bodySmall, color = Muted) }
            }
        }
    }
}

@Composable
private fun Header(engine: EngineState) {
    Row(
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BrandMark(44.dp)
        Spacer(Modifier.width(12.dp))
        Column {
            Text("Descargo", style = MaterialTheme.typography.headlineMedium)
            val (status, color) = when (engine) {
                EngineState.Starting -> "Preparando motor… (solo la primera vez)" to Warn
                is EngineState.Updating -> "Actualizando motor…" to Warn
                is EngineState.Ready -> "Listo · yt-dlp ${engine.version ?: ""}" to Color(0xFF3DD68C)
                is EngineState.Failed -> "Error del motor: ${engine.message}" to Accent
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                // Pulses while the engine is busy, steady when ready.
                val busy = engine !is EngineState.Ready && engine !is EngineState.Failed
                val pulse = rememberInfiniteTransition(label = "dot")
                val a by pulse.animateFloat(
                    initialValue = 1f, targetValue = if (busy) 0.25f else 1f,
                    animationSpec = infiniteRepeatable(tween(700), RepeatMode.Reverse), label = "dotA",
                )
                Box(Modifier.size(7.dp).clip(CircleShape).background(color.copy(alpha = a)))
                Spacer(Modifier.width(6.dp))
                Text(status, style = MaterialTheme.typography.bodySmall, color = Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** Skeleton of the result card that shimmers while yt-dlp reads the page. */
@Composable
private fun LoadingCard() {
    val t = rememberInfiniteTransition(label = "shimmer")
    val x by t.animateFloat(-1f, 2f, infiniteRepeatable(tween(1100, easing = LinearEasing)), label = "x")
    val base = MaterialTheme.colorScheme.surfaceContainerHighest
    val shine = Brush.linearGradient(
        listOf(base, base.copy(alpha = 0.35f), base),
        start = Offset(x * 800f, 0f),
        end = Offset(x * 800f + 400f, 200f),
    )
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).clip(MaterialTheme.shapes.large).background(CardBg)) {
        Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).background(shine))
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.fillMaxWidth(0.85f).height(14.dp).clip(CircleShape).background(shine))
            Box(Modifier.fillMaxWidth(0.5f).height(10.dp).clip(CircleShape).background(shine))
        }
    }
}

@Composable
private fun ClipboardCard(link: String, onUse: () -> Unit, onDismiss: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp).clip(RoundedCornerShape(12.dp))
            .background(Selected).padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text("Enlace copiado", style = MaterialTheme.typography.labelMedium)
            Text(link, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, color = Muted)
        }
        TextButton(onClick = onUse) { Text("Analizar") }
        TextButton(onClick = onDismiss) { Text("✕", color = Muted) }
    }
}

@Composable
private fun UrlBox(vm: MainViewModel) {
    val clipboard = LocalClipboardManager.current
    Column(
        Modifier.padding(horizontal = 16.dp).clip(MaterialTheme.shapes.large).background(CardBg).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        OutlinedTextField(
            value = vm.url,
            onValueChange = { vm.url = it },
            placeholder = { Text("Pega el enlace del video, lista o canal") },
            singleLine = true,
            shape = MaterialTheme.shapes.medium,
            modifier = Modifier.fillMaxWidth(),
            trailingIcon = {
                TextButton(onClick = {
                    clipboard.getText()?.text?.let { vm.analyzeLink(it) }
                }) { Text("Pegar") }
            },
        )
        val loading = vm.state == UiState.Loading
        GradientButton(
            if (loading) "Analizando…" else "Buscar formatos",
            enabled = !loading,
            loading = loading,
            onClick = { vm.analyze() },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Hint() {
    Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Funciona con", style = MaterialTheme.typography.labelLarge, color = Muted)
        val sites = listOf(
            "YouTube" to Color(0xFFFF3B30), "TikTok" to Color(0xFF25F4EE), "Facebook" to Color(0xFF1877F2),
            "Pinterest" to Color(0xFFE60023), "Instagram" to Color(0xFFD62976), "X" to Color(0xFF9AA0A6),
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            sites.forEach { (name, c) ->
                Row(
                    Modifier.clip(CircleShape).background(c.copy(alpha = 0.14f)).padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.size(8.dp).clip(CircleShape).background(c))
                    Spacer(Modifier.width(6.dp))
                    Text(name, style = MaterialTheme.typography.labelMedium)
                }
            }
            Text("+1000 sitios", style = MaterialTheme.typography.labelMedium, color = Muted, modifier = Modifier.padding(vertical = 6.dp))
        }
        Text(
            "Videos, listas y canales. También «Compartir → Descargo» desde otras apps, o la pestaña Navegador.",
            style = MaterialTheme.typography.bodySmall, color = Muted,
        )
    }
}

@Composable
fun Note(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = Muted, modifier = Modifier.padding(horizontal = 16.dp))
}

@Composable
fun PrimaryButton(text: String, enabled: Boolean = true, onClick: () -> Unit) {
    Box(Modifier.padding(horizontal = 16.dp)) { GradientButton(text, enabled = enabled, height = 54.dp, onClick = onClick) }
}

/** Pill button on the brand gradient; shrinks a bit when pressed. */
@Composable
fun GradientButton(
    text: String, enabled: Boolean = true, loading: Boolean = false, height: Dp = 50.dp, onClick: () -> Unit,
) {
    val src = remember { MutableInteractionSource() }
    val alpha by animateFloatAsState(if (enabled || loading) 1f else 0.4f, label = "btnA")
    Box(
        Modifier.fillMaxWidth().height(height).pressScale(src).clip(CircleShape)
            .background(BrandGradient, alpha = alpha)
            .clickable(interactionSource = src, indication = ripple(color = Color.White), enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AnimatedVisibility(loading) {
                Row {
                    CircularProgressIndicator(Modifier.size(18.dp), color = Color.White, strokeWidth = 2.dp)
                    Spacer(Modifier.width(10.dp))
                }
            }
            Text(text, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

private fun flacNote(info: MediaInfo): String {
    val src = info.bestAudioKbps?.let { " (la mejor pista aquí es ~$it kbps)" } ?: ""
    return "FLAC guarda sin pérdidas la pista elegida$src. Como el sitio entrega audio comprimido, " +
        "FLAC no suena mejor que el original: es útil para editar o para equipos que solo leen FLAC."
}

@Composable
private fun MediaHeader(info: MediaInfo) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = CardBg),
    ) {
        Thumb(info.thumbnail, Modifier.fillMaxWidth().aspectRatio(16f / 9f))
        Column(Modifier.padding(12.dp)) {
            Text(info.title, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            val meta = listOfNotNull(info.uploader, info.durationSec?.let(::formatDuration), info.site)
            Text(meta.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = Muted)
        }
    }
}

@Composable
private fun KindTabs(current: Kind, onSelect: (Kind) -> Unit) {
    PrimaryScrollableTabRow(
        selectedTabIndex = Kind.formatTabs.indexOf(current).coerceAtLeast(0),
        edgePadding = 16.dp,
        containerColor = Color.Transparent,
    ) {
        Kind.formatTabs.forEach { k ->
            Tab(selected = k == current, onClick = { onSelect(k) }, text = { Text(k.title) })
        }
    }
}

@Composable
private fun OptionRow(opt: DownloadOption, selected: Boolean, onClick: () -> Unit) {
    val bg by animateColorAsState(if (selected) Primary.copy(alpha = 0.16f) else CardBg, tween(220), label = "optBg")
    val edge by animateColorAsState(if (selected) Primary else Color.Transparent, tween(220), label = "optEdge")
    val src = remember { MutableInteractionSource() }
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .pressScale(src)
            .clip(MaterialTheme.shapes.medium)
            .background(bg)
            .border(1.5.dp, edge, MaterialTheme.shapes.medium)
            .clickable(interactionSource = src, indication = ripple(), onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Column(Modifier.weight(1f)) {
            Text(opt.label, fontWeight = FontWeight.Medium)
            Text(opt.detail, style = MaterialTheme.typography.bodySmall, color = Muted)
            opt.warning?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Warn) }
        }
        opt.sizeBytes?.let {
            Text("~" + formatSize(it), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(end = 8.dp))
        }
    }
}

@Composable
private fun ClipBox(vm: MainViewModel) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp).clip(MaterialTheme.shapes.medium).background(CardBg)
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Recortar", fontWeight = FontWeight.Medium)
                Text("Baja solo un fragmento (ej. 1:05 a 2:30)", style = MaterialTheme.typography.bodySmall, color = Muted)
            }
            Switch(checked = vm.clipOn, onCheckedChange = { vm.clipOn = it })
        }
        AnimatedVisibility(vm.clipOn, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
          Column {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 6.dp)) {
                OutlinedTextField(
                    value = vm.clipStart, onValueChange = { vm.clipStart = it }, singleLine = true,
                    label = { Text("Desde") }, modifier = Modifier.weight(1f),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
                )
                OutlinedTextField(
                    value = vm.clipEnd, onValueChange = { vm.clipEnd = it }, singleLine = true,
                    label = { Text("Hasta") }, modifier = Modifier.weight(1f),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
                )
            }
            Text(
                "Formato min:seg o h:min:seg. El corte es exacto pero tarda un poco más (se recodifica en los bordes).",
                style = MaterialTheme.typography.bodySmall, color = Muted, modifier = Modifier.padding(top = 4.dp),
            )
          }
        }
    }
}

@Composable
private fun ActiveRow(item: HistoryItem, p: Progress?, onCancel: () -> Unit, onPause: () -> Unit, onResume: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp).clip(MaterialTheme.shapes.medium).background(CardBg)
            .padding(start = 10.dp, end = 4.dp, top = 10.dp, bottom = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Thumb(item.thumbnail, Modifier.size(width = 72.dp, height = 40.dp))
            Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
                Text(item.title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium)
                val status = when {
                    item.status == Status.PAUSED -> "En pausa"
                    item.status == Status.QUEUED -> "En cola"
                    p == null -> "Preparando…"
                    else -> "${p.line} ${p.percent.toInt()}%" + if (p.eta > 0) " · ${formatDuration(p.eta.toDouble())}" else ""
                }
                Text("${item.optionLabel} — $status", style = MaterialTheme.typography.bodySmall, color = Muted, maxLines = 1)
            }
            if (item.status == Status.PAUSED) TextButton(onClick = onResume) { Text("Reanudar") }
            else TextButton(onClick = onPause) { Text("Pausar") }
            TextButton(onClick = onCancel) { Text("✕", color = Muted) }
        }
        AnimatedVisibility(item.status == Status.RUNNING) {
            val target = (p?.percent ?: 0f) / 100f
            val pct by animateFloatAsState(target, tween(450), label = "dl")
            val mod = Modifier.fillMaxWidth().padding(top = 8.dp, end = 6.dp).height(6.dp).clip(CircleShape)
            if (target > 0f) LinearProgressIndicator(
                progress = { pct }, color = Primary, trackColor = Divider, modifier = mod,
                gapSize = 0.dp, drawStopIndicator = {},
            )
            else LinearProgressIndicator(color = Primary, trackColor = Divider, modifier = mod)
        }
    }
}
