package com.descargo.app.ui

import android.view.ViewGroup
import android.webkit.WebView
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.descargo.app.R
import java.net.URLEncoder

private val SITES = listOf(
    "YouTube" to "https://m.youtube.com",
    "TikTok" to "https://www.tiktok.com",
    "Facebook" to "https://m.facebook.com/watch",
    "Instagram" to "https://www.instagram.com",
    "Pinterest" to "https://www.pinterest.com",
    "X" to "https://x.com",
)

@Composable
fun BrowserScreen(vm: MainViewModel, webView: WebView) {
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    var address by remember { mutableStateOf(vm.browserUrl) }
    var editing by remember { mutableStateOf(false) }
    LaunchedEffect(vm.browserUrl) { if (!editing) address = vm.browserUrl }

    // Pause video playback and JS timers while the tab is hidden.
    DisposableEffect(Unit) {
        webView.onResume()
        onDispose { webView.onPause() }
    }

    fun go(input: String) {
        val t = input.trim()
        val target = when {
            t.startsWith("http://") || t.startsWith("https://") -> t
            t.contains('.') && !t.contains(' ') -> "https://$t"
            else -> "https://www.google.com/search?q=" + URLEncoder.encode(t, "UTF-8")
        }
        webView.loadUrl(target)
        keyboard?.hide()
        focus.clearFocus()
        editing = false
    }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(start = 8.dp, end = 4.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = address,
                onValueChange = { address = it },
                singleLine = true,
                placeholder = { Text("Buscar o escribir dirección") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = { go(address) }),
                modifier = Modifier.weight(1f).onFocusChanged { editing = it.isFocused },
            )
            TextButton(onClick = { webView.reload() }) { Text("↻") }
        }
        LazyRow(contentPadding = PaddingValues(horizontal = 8.dp), horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(6.dp)) {
            items(SITES) { (name, url) ->
                AssistChip(onClick = { webView.loadUrl(url) }, label = { Text(name) })
            }
        }
        if (vm.browserProgress < 100) {
            LinearProgressIndicator(progress = { vm.browserProgress / 100f }, color = Primary, modifier = Modifier.fillMaxWidth())
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            AndroidView(
                factory = {
                    (webView.parent as? ViewGroup)?.removeView(webView)
                    webView
                },
                modifier = Modifier.fillMaxSize(),
            )
            if (MainViewModel.isMediaUrl(vm.browserUrl)) {
                ExtendedFloatingActionButton(
                    onClick = { vm.analyzeLink(vm.browserUrl) },
                    containerColor = Primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                    icon = { Icon(painterResource(R.drawable.ic_stat_download), null) },
                    text = { Text("Descargar") },
                    modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
                )
            }
        }
    }
}
