package com.descargo.app.ui

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.ui.window.DialogProperties
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.descargo.app.R

@Composable
fun MainScreen(vm: MainViewModel) {
    val ctx = LocalContext.current
    val history by vm.history.collectAsStateWithLifecycle()
    val active = history.count { it.isActive }

    LaunchedEffect(vm.message) {
        vm.message?.let { Toast.makeText(ctx, it, Toast.LENGTH_LONG).show(); vm.message = null }
    }

    val settings by vm.settings.collectAsStateWithLifecycle()
    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { vm.onFolderPicked(it) }

    // First run: ask where downloads go; Audio/ and Video/ are created inside.
    if (!settings.folderChosen) {
        AlertDialog(
            onDismissRequest = {},
            properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
            title = { Text("¿Dónde guardo tus descargas?") },
            text = {
                Text(
                    "Elige una carpeta (o crea una nueva desde el selector). Dentro haré dos subcarpetas: " +
                        "«Audio» para MP3, FLAC y audio original, y «Video» para los videos. " +
                        "Cada descarga irá sola a la que corresponde.\n\nPuedes cambiarla después en Ajustes."
                )
            },
            confirmButton = { TextButton(onClick = { pickFolder.launch(null) }) { Text("Elegir carpeta", color = Primary) } },
            dismissButton = { TextButton(onClick = vm::skipFolderSetup) { Text("Usar Descargas/Descargo") } },
        )
    }

    if (vm.pendingMove > 0) {
        AlertDialog(
            onDismissRequest = { vm.pendingMove = 0 },
            title = { Text("¿Ordenar tus descargas anteriores?") },
            text = {
                Text(
                    "Tienes ${vm.pendingMove} archivo(s) descargados antes en otra ubicación. " +
                        "¿Los muevo a la nueva carpeta, separando audio y video?"
                )
            },
            confirmButton = { TextButton(onClick = vm::moveEarlierDownloads) { Text("Mover", color = Primary) } },
            dismissButton = { TextButton(onClick = { vm.pendingMove = 0 }) { Text("Ahora no") } },
        )
    }

    // One WebView for the whole session so the browser keeps its page across tab switches.
    val webView = rememberBrowser(vm)
    BackHandler(enabled = vm.screen == Screen.BROWSER && vm.browserCanGoBack) { webView.goBack() }
    BackHandler(enabled = vm.screen != Screen.HOME && !(vm.screen == Screen.BROWSER && vm.browserCanGoBack)) {
        vm.screen = Screen.HOME
    }

    Scaffold(
        
        bottomBar = {
            NavigationBar(containerColor = CardBg) {
                Screen.entries.forEach { s ->
                    val icon = when (s) {
                        Screen.HOME -> R.drawable.ic_nav_home
                        Screen.BROWSER -> R.drawable.ic_nav_browser
                        Screen.LIBRARY -> R.drawable.ic_nav_library
                        Screen.SETTINGS -> R.drawable.ic_nav_settings
                    }
                    NavigationBarItem(
                        selected = vm.screen == s,
                        onClick = { vm.screen = s },
                        icon = {
                            if (s == Screen.HOME && active > 0) {
                                BadgedBox(badge = { Badge { Text("$active") } }) {
                                    Icon(painterResource(icon), s.label)
                                }
                            } else Icon(painterResource(icon), s.label)
                        },
                        label = { Text(s.label) },
                        colors = NavigationBarItemDefaults.colors(indicatorColor = Selected),
                    )
                }
            }
        },
    ) { pad ->
        Box(Modifier.fillMaxSize().padding(pad)) {
            when (vm.screen) {
                Screen.HOME -> HomeScreen(vm)
                Screen.BROWSER -> BrowserScreen(vm, webView)
                Screen.LIBRARY -> LibraryScreen(vm)
                Screen.SETTINGS -> SettingsScreen(vm)
            }
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun rememberBrowser(vm: MainViewModel): WebView {
    val ctx = LocalContext.current
    return remember {
        WebView(ctx).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.mediaPlaybackRequiresUserGesture = true
            settings.setSupportMultipleWindows(false)
            CookieManager.getInstance().setAcceptCookie(true)
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    val scheme = request.url.scheme ?: return true
                    // Block intent:// / app deep links so pages stay inside the browser.
                    return scheme != "http" && scheme != "https"
                }

                override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                    vm.browserUrl = url
                }

                override fun doUpdateVisitedHistory(view: WebView, url: String, isReload: Boolean) {
                    // Fires for SPA navigation (YouTube/TikTok change URL without reloading).
                    vm.browserUrl = url
                    vm.browserCanGoBack = view.canGoBack()
                }

                override fun onPageFinished(view: WebView, url: String) {
                    vm.browserCanGoBack = view.canGoBack()
                    CookieManager.getInstance().flush()
                }
            }
            webChromeClient = object : WebChromeClient() {
                override fun onProgressChanged(view: WebView, newProgress: Int) {
                    vm.browserProgress = newProgress
                }
            }
            loadUrl(vm.browserUrl)
        }
    }
}
