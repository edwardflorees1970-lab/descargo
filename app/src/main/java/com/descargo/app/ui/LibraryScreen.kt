package com.descargo.app.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.LaunchedEffect
import com.descargo.app.engine.Tagger
import com.descargo.app.engine.Tags
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.descargo.app.data.HistoryItem
import com.descargo.app.data.Status
import com.descargo.app.engine.Kind

private enum class Filter(val label: String) { ALL("Todo"), VIDEO("Videos"), AUDIO("Audio"), FAILED("Fallidas") }

@Composable
fun LibraryScreen(vm: MainViewModel) {
    val ctx = LocalContext.current
    val history by vm.history.collectAsStateWithLifecycle()
    var query by rememberSaveable { mutableStateOf("") }
    var filter by rememberSaveable { mutableStateOf(Filter.ALL) }
    var toDelete by remember { mutableStateOf<HistoryItem?>(null) }
    var confirmClear by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<HistoryItem?>(null) }

    val shown = history.asSequence()
        .filterNot { it.isPending }
        .filter {
            when (filter) {
                Filter.ALL -> true
                Filter.VIDEO -> it.kind == Kind.VIDEO && it.status == Status.DONE
                Filter.AUDIO -> it.kind != Kind.VIDEO && it.status == Status.DONE
                Filter.FAILED -> it.status == Status.FAILED || it.status == Status.CANCELLED
            }
        }
        .filter { query.isBlank() || it.title.contains(query, true) || it.site.contains(query, true) }
        .sortedByDescending { it.createdAt }
        .toList()
    val totalBytes = history.filter { it.status == Status.DONE }.sumOf { it.sizeBytes ?: 0L }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Biblioteca", fontSize = 26.sp, fontWeight = FontWeight.Bold)
                    Text(
                        history.count { it.status == Status.DONE }.let { n -> "$n ${if (n == 1) "archivo" else "archivos"}" } +
                            " · ${formatSize(totalBytes)}",
                        style = MaterialTheme.typography.bodySmall, color = Muted,
                    )
                }
                TextButton(onClick = { confirmClear = true }) { Text("Limpiar") }
            }
        }
        item {
            OutlinedTextField(
                value = query, onValueChange = { query = it }, singleLine = true,
                placeholder = { Text("Buscar por título o sitio") },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            )
        }
        item {
            LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(Filter.entries) { f ->
                    FilterChip(
                        selected = filter == f, onClick = { filter = f }, label = { Text(f.label) },
                        colors = FilterChipDefaults.filterChipColors(selectedContainerColor = Selected),
                    )
                }
            }
        }
        if (shown.isEmpty()) item {
            Note(if (history.isEmpty()) "Aún no hay descargas. Pega un enlace en Inicio o usa el Navegador." else "Nada coincide con el filtro.")
        }
        items(shown, key = { it.id }) { item ->
            LibraryRow(
                item,
                onOpen = { if (item.uri != null) openFile(ctx, Uri.parse(item.uri), item.mime ?: "*/*") },
                onShare = { if (item.uri != null) shareFile(ctx, Uri.parse(item.uri), item.mime ?: "*/*", item.title) },
                onDelete = { toDelete = item },
                onRetry = { vm.retry(item) },
                onTags = { editing = item },
            )
        }
    }

    editing?.let { item -> TagDialog(vm, item, onClose = { editing = null }) }

    toDelete?.let { item ->
        var alsoFile by remember(item.id) { mutableStateOf(item.status == Status.DONE) }
        AlertDialog(
            onDismissRequest = { toDelete = null },
            title = { Text("¿Quitar de la biblioteca?") },
            text = {
                Column {
                    Text(item.title, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    if (item.status == Status.DONE) Row(
                        Modifier.clickable { alsoFile = !alsoFile }, verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(checked = alsoFile, onCheckedChange = { alsoFile = it })
                        Text("Borrar también el archivo del teléfono")
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { vm.delete(item, alsoFile); toDelete = null }) { Text(if (alsoFile) "Borrar" else "Quitar", color = Primary) }
            },
            dismissButton = { TextButton(onClick = { toDelete = null }) { Text("Cancelar") } },
        )
    }

    if (confirmClear) AlertDialog(
        onDismissRequest = { confirmClear = false },
        title = { Text("¿Limpiar historial?") },
        text = { Text("Se borra la lista; los archivos se quedan en el teléfono.") },
        confirmButton = { TextButton(onClick = { vm.clearHistory(); confirmClear = false }) { Text("Limpiar", color = Primary) } },
        dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancelar") } },
    )
}

@Composable
private fun TagDialog(vm: MainViewModel, item: HistoryItem, onClose: () -> Unit) {
    var title by remember(item.id) { mutableStateOf(item.title) }
    var artist by remember(item.id) { mutableStateOf("") }
    var album by remember(item.id) { mutableStateOf("") }
    var cover by remember(item.id) { mutableStateOf<Uri?>(null) }
    LaunchedEffect(item.id) {
        val t = withContext(Dispatchers.IO) { vm.readTags(item) }
        if (t.title.isNotBlank()) title = t.title
        artist = t.artist
        album = t.album
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { cover = it }
    val coverOk = Tagger.supportsCover(item.fileName ?: "")

    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Etiquetas") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(title, { title = it }, label = { Text("Título") }, singleLine = true)
                OutlinedTextField(artist, { artist = it }, label = { Text("Artista") }, singleLine = true)
                OutlinedTextField(album, { album = it }, label = { Text("Álbum") }, singleLine = true)
                if (coverOk) {
                    TextButton(onClick = {
                        picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    }) { Text(if (cover == null) "Cambiar portada…" else "✓ Portada elegida") }
                } else {
                    Text("Este formato no admite portada.", style = MaterialTheme.typography.bodySmall, color = Muted)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                vm.saveTags(item, Tags(title.trim(), artist.trim(), album.trim()), cover)
                onClose()
            }) { Text("Guardar", color = Primary) }
        },
        dismissButton = { TextButton(onClick = onClose) { Text("Cancelar") } },
    )
}

@Composable
private fun LibraryRow(
    item: HistoryItem,
    onOpen: () -> Unit,
    onShare: () -> Unit,
    onDelete: () -> Unit,
    onRetry: () -> Unit,
    onTags: () -> Unit,
) {
    val done = item.status == Status.DONE
    Column(Modifier.fillMaxWidth().clickable(enabled = done, onClick = onOpen).padding(horizontal = 16.dp, vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Thumb(item.thumbnail, Modifier.width(112.dp).aspectRatio(16f / 9f), fallback = item.kind.title)
            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                Text(item.title, maxLines = 2, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium)
                val meta = listOfNotNull(item.optionLabel, item.sizeBytes?.let(::formatSize), formatDate(item.createdAt))
                Text(meta.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = Muted, maxLines = 2)
                when (item.status) {
                    Status.FAILED -> Text(item.error ?: "Falló", style = MaterialTheme.typography.bodySmall, color = Primary, maxLines = 3)
                    Status.CANCELLED -> Text("Cancelada", style = MaterialTheme.typography.bodySmall, color = Warn)
                    else -> {}
                }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            if (done) {
                TextButton(onClick = onOpen) { Text("Abrir") }
                TextButton(onClick = onShare) { Text("Compartir") }
                if (item.kind != Kind.VIDEO) TextButton(onClick = onTags) { Text("Etiquetas") }
            } else {
                TextButton(onClick = onRetry) { Text("Reintentar") }
            }
            TextButton(onClick = onDelete) { Text(if (done) "Borrar" else "Quitar", color = Muted) }
        }
        HorizontalDivider(color = Divider)
    }
}
