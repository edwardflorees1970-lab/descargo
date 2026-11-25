package com.descargo.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.lazy.items
import androidx.compose.ui.text.style.TextOverflow
import com.descargo.app.data.Cookies
import com.descargo.app.data.FolderMode
import com.descargo.app.data.NameTemplate
import com.descargo.app.data.ThemeMode
import com.descargo.app.work.Storage
import com.descargo.app.engine.EngineState
import com.descargo.app.engine.Kind

@Composable
fun SettingsScreen(vm: MainViewModel) {
    val s by vm.settings.collectAsState()
    val engine by vm.engineState.collectAsState()
    var subLangs by remember(s.subLangs) { mutableStateOf(s.subLangs) }
    val sites = remember(vm.screen) { Cookies.loggedInSites() }
    val subs by vm.subscriptions.collectAsState()
    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { vm.onFolderPicked(it) }
    var folder by remember(s.folderName) { mutableStateOf(s.folderName) }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 32.dp)) {
        item { Text("Ajustes", fontSize = 26.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(16.dp)) }

        item { Section("Formato predeterminado") }
        item {
            Choice(
                "Calidad de video", s.videoHeight,
                listOf(0 to "La mejor disponible", 2160 to "4K (2160p)", 1440 to "2K (1440p)", 1080 to "1080p",
                    720 to "720p", 480 to "480p", 360 to "360p"),
            ) { v -> vm.updateSettings { it.copy(videoHeight = v) } }
        }
        item {
            Choice("Bitrate MP3", s.mp3Kbps, listOf(320, 256, 192, 160, 128).map { it to "$it kbps" }) { v ->
                vm.updateSettings { it.copy(mp3Kbps = v) }
            }
        }
        item {
            Choice("Pestaña al analizar", s.openTab, Kind.entries.map { it to it.title }) { v ->
                vm.updateSettings { it.copy(openTab = v) }
            }
        }

        item { Section("Descargas") }
        item {
            Choice("Descargas simultáneas", s.maxConcurrent, (1..5).map { it to "$it a la vez" }) { v ->
                vm.updateSettings { it.copy(maxConcurrent = v) }
            }
        }
        item {
            Toggle("Solo con Wi-Fi", "Las descargas esperan hasta tener Wi-Fi", s.wifiOnly) { v ->
                vm.updateSettings { it.copy(wifiOnly = v) }
            }
        }
        item {
            Toggle("Detectar enlaces copiados", "Al abrir la app ofrece analizar el enlace del portapapeles", s.watchClipboard) { v ->
                vm.updateSettings { it.copy(watchClipboard = v) }
            }
        }

        item {
            Choice(
                "Límite de velocidad", s.rateLimit,
                listOf("" to "Sin límite", "500K" to "500 KB/s", "1M" to "1 MB/s", "2M" to "2 MB/s", "5M" to "5 MB/s"),
            ) { v -> vm.updateSettings { it.copy(rateLimit = v) } }
        }

        item { Section("YouTube") }
        item {
            Toggle("Quitar patrocinios (SponsorBlock)", "Corta publicidad integrada, autopromoción y «suscríbete» usando la base comunitaria SponsorBlock", s.sponsorBlock) { v ->
                vm.updateSettings { it.copy(sponsorBlock = v) }
            }
        }
        item {
            Toggle("Guardar capítulos", "Los capítulos del video quedan marcados en el archivo", s.chapters) { v ->
                vm.updateSettings { it.copy(chapters = v) }
            }
        }
        item {
            Toggle("Separar «Artista - Canción»", "En audio, usa ese patrón del título para llenar artista y título", s.splitArtist) { v ->
                vm.updateSettings { it.copy(splitArtist = v) }
            }
        }

        item { Section("Suscripciones") }
        item {
            Choice("Revisar canales cada", s.subsIntervalH, listOf(1, 3, 6, 12, 24).map { it to "$it h" }) { v ->
                vm.updateSettings { it.copy(subsIntervalH = v) }
            }
        }
        if (subs.isEmpty()) item {
            Note("Abre una lista o canal en Inicio y toca «Suscribirse» para bajar sus videos nuevos automáticamente.")
        }
        items(subs, key = { it.id }) { sub ->
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(sub.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    val last = if (sub.lastCheck > 0) "revisado ${formatDate(sub.lastCheck)}" else "sin revisar"
                    Text("${sub.presetLabel} · $last", style = MaterialTheme.typography.bodySmall, color = Muted)
                    sub.lastError?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Primary, maxLines = 2) }
                }
                TextButton(onClick = { vm.unsubscribe(sub.id) }) { Text("Quitar", color = Muted) }
            }
        }
        if (subs.isNotEmpty()) item {
            TextButton(onClick = vm::checkSubscriptionsNow, modifier = Modifier.padding(horizontal = 8.dp)) { Text("Revisar ahora") }
        }

        item { Section("Apariencia") }
        item {
            Choice("Tema", s.theme, ThemeMode.entries.map { it to it.label }) { v -> vm.updateSettings { it.copy(theme = v) } }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) item {
            Toggle("Colores de tu fondo (Material You)", "Usa la paleta de tu fondo de pantalla", s.dynamicColor) { v ->
                vm.updateSettings { it.copy(dynamicColor = v) }
            }
        }

        item { Section("Archivos") }
        item {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Carpeta de descargas")
                    Text(
                        if (s.treeUri.isNotBlank()) Storage.treeLabel(s.treeUri) + "  (con Audio y Video adentro)"
                        else "Predeterminada (sin elegir)",
                        style = MaterialTheme.typography.bodySmall, color = Muted,
                    )
                }
                TextButton(onClick = { pickFolder.launch(null) }) { Text(if (s.treeUri.isBlank()) "Elegir" else "Cambiar") }
            }
        }
        if (s.treeUri.isNotBlank()) {
            item {
                Row(Modifier.padding(horizontal = 8.dp)) {
                    TextButton(onClick = vm::countMovable, enabled = !vm.moving) {
                        Text(if (vm.moving) "Moviendo…" else "Ordenar descargas anteriores")
                    }
                    TextButton(onClick = vm::useDefaultFolder) { Text("Usar predeterminada", color = Muted) }
                }
            }
        } else {
            item {
                Choice("Carpeta predeterminada", s.folderMode, FolderMode.entries.map { it to it.label }) { v ->
                    vm.updateSettings { it.copy(folderMode = v) }
                }
            }
            item {
                OutlinedTextField(
                    value = folder,
                    onValueChange = { folder = it; vm.updateSettings { st -> st.copy(folderName = it) } },
                    label = { Text("Nombre de la carpeta") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }
        }
        item {
            Toggle("Subcarpeta por sitio", "Ej. Audio/YouTube, Video/TikTok", s.bySite) { v ->
                vm.updateSettings { it.copy(bySite = v) }
            }
        }
        item {
            Choice("Nombre de archivo", s.nameTemplate, NameTemplate.entries.map { it to it.label }) { v ->
                vm.updateSettings { it.copy(nameTemplate = v) }
            }
        }
        item {
            Note(
                "Audio: " + Storage.describeTarget(Kind.MP3) + "\nVideo: " + Storage.describeTarget(Kind.VIDEO)
            )
        }
        item {
            Note("Atajo: agrega el botón «Descargar copiado» en tus Ajustes rápidos (desliza la barra de notificaciones → editar).")
        }

        item { Section("Subtítulos (videos)") }
        item {
            Toggle("Incrustar subtítulos", "Se agregan como pista dentro del MP4", s.embedSubs) { v ->
                vm.updateSettings { it.copy(embedSubs = v) }
            }
        }
        if (s.embedSubs) {
            item {
                Toggle("Incluir subtítulos automáticos", "Los generados por YouTube cuando no hay oficiales", s.autoSubs) { v ->
                    vm.updateSettings { it.copy(autoSubs = v) }
                }
            }
            item {
                OutlinedTextField(
                    value = subLangs,
                    onValueChange = { subLangs = it; vm.updateSettings { st -> st.copy(subLangs = it) } },
                    label = { Text("Idiomas (ej. es.*,en.*)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }
        }

        item { Section("Sesión") }
        item {
            Toggle(
                "Usar mi sesión del navegador",
                "Para videos privados o con restricción de edad. Inicia sesión en la pestaña Navegador. " +
                    (if (sites.isNotEmpty()) "Sesión iniciada en: ${sites.joinToString()}." else "Aún no has iniciado sesión en ningún sitio."),
                s.useCookies,
            ) { v -> vm.updateSettings { it.copy(useCookies = v) } }
        }
        item {
            Note(
                "Tus cookies solo se guardan en este teléfono y se pasan a yt-dlp. Google a veces bloquea " +
                    "el inicio de sesión dentro de apps; Facebook, Instagram y TikTok suelen funcionar."
            )
        }
        item { TextButton(onClick = vm::clearSession, modifier = Modifier.padding(horizontal = 8.dp)) { Text("Cerrar todas las sesiones") } }

        item { Section("Motor") }
        item {
            val v = when (val e = engine) {
                is EngineState.Ready -> e.version ?: "?"
                is EngineState.Updating -> "actualizando…"
                EngineState.Starting -> "iniciando…"
                is EngineState.Failed -> "error"
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("yt-dlp $v")
                    Text("Se actualiza solo cada 12 h", style = MaterialTheme.typography.bodySmall, color = Muted)
                }
                TextButton(onClick = vm::updateEngine, enabled = engine is EngineState.Ready) { Text("Actualizar") }
            }
        }
        item {
            Toggle("Canal nightly", "Versiones diarias: arreglos más rápidos cuando un sitio cambia", s.nightlyEngine) { v ->
                vm.updateSettings { it.copy(nightlyEngine = v) }
            }
        }
        item { Note("Archivos en Descargas/Descargo. Descargo usa yt-dlp, ffmpeg y youtubedl-android (GPL-3.0).") }
    }
}

@Composable
private fun Section(title: String) {
    Column {
        HorizontalDivider(color = Divider, modifier = Modifier.padding(top = 8.dp))
        Text(title, color = Primary, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 4.dp))
    }
}

@Composable
private fun Toggle(title: String, desc: String, value: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable { onChange(!value) }.padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title)
            Text(desc, style = MaterialTheme.typography.bodySmall, color = Muted)
        }
        Switch(checked = value, onCheckedChange = onChange, colors = SwitchDefaults.colors(checkedTrackColor = Primary))
    }
}

@Composable
private fun <T> Choice(title: String, value: T, options: List<Pair<T, String>>, onSelect: (T) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().clickable { open = true }.padding(horizontal = 16.dp, vertical = 10.dp)) {
        Text(title)
        Text(options.firstOrNull { it.first == value }?.second ?: "$value", style = MaterialTheme.typography.bodySmall, color = Muted)
    }
    if (open) AlertDialog(
        onDismissRequest = { open = false },
        title = { Text(title) },
        text = {
            Column {
                options.forEach { (v, label) ->
                    Row(
                        Modifier.fillMaxWidth().clickable { onSelect(v); open = false }.padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = v == value, onClick = { onSelect(v); open = false })
                        Text(label)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { open = false }) { Text("Cerrar") } },
    )
}
