package com.descargo.app.ui

import android.Manifest
import android.app.PendingIntent
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.animation.DecelerateInterpolator
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.descargo.app.data.ThemeMode

class MainActivity : ComponentActivity() {
    companion object {
        const val EXTRA_CLIPBOARD = "analyze_clipboard"
        const val EXTRA_OPEN_PLAYER = "open_player"
    }

    private val vm: MainViewModel by viewModels()
    private var analyzeClipboardOnFocus = false

    private val permissions =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        val splash = installSplashScreen()
        super.onCreate(savedInstanceState)
        // Splash leaves by growing and fading instead of cutting away.
        splash.setOnExitAnimationListener { v ->
            runCatching {
                v.iconView.animate().scaleX(1.35f).scaleY(1.35f).setDuration(260).start()
                v.view.animate().alpha(0f).setDuration(280)
                    .setInterpolator(DecelerateInterpolator())
                    .withEndAction { v.remove() }.start()
            }.onFailure { v.remove() }
        }
        enableEdgeToEdge()
        askPermissions()
        if (savedInstanceState == null) handleIntent(intent)
        setContent {
            val s by vm.settings.collectAsStateWithLifecycle()
            val dark = when (s.theme) {
                ThemeMode.SYSTEM -> isSystemInDarkTheme()
                ThemeMode.DARK -> true
                ThemeMode.LIGHT -> false
            }
            LaunchedEffect(dark) {
                // Status/navigation bar icons must contrast with the chosen theme, not the system one.
                val style = if (dark) SystemBarStyle.dark(Color.TRANSPARENT)
                else SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
                enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
            }
            DescargoTheme(s.theme, s.dynamicColor) { MainScreen(vm) }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    // Android 10+ only lets the focused app read the clipboard, so check when focus arrives.
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus) return
        val cm = getSystemService(ClipboardManager::class.java) ?: return
        val text = runCatching { cm.primaryClip?.getItemAt(0)?.coerceToText(this) }.getOrNull()
        if (analyzeClipboardOnFocus) {
            analyzeClipboardOnFocus = false
            val link = MainViewModel.extractUrl(text?.toString())
            if (link != null) vm.handleShared(link) else vm.message = "No hay ningún enlace copiado"
        } else {
            vm.onClipboard(text)
        }
    }

    private fun handleIntent(intent: Intent?) {
        if (intent?.getBooleanExtra(EXTRA_OPEN_PLAYER, false) == true) {
            vm.player.connect()
            vm.showPlayer = vm.player.isVideo
            return
        }
        if (intent?.getBooleanExtra(EXTRA_CLIPBOARD, false) == true) {
            analyzeClipboardOnFocus = true
            return
        }
        // Links shared from YouTube / TikTok / Facebook / Pinterest open straight into analysis.
        if (intent?.action != Intent.ACTION_SEND) return
        val link = MainViewModel.extractUrl(intent.getStringExtra(Intent.EXTRA_TEXT)) ?: return
        vm.handleShared(link)
    }

    private fun askPermissions() {
        val wanted = buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
        if (wanted.isNotEmpty()) permissions.launch(wanted.toTypedArray())
    }
}

/** Quick Settings tile: opens Descargo and analyzes whatever link is in the clipboard. */
class ClipboardTileService : TileService() {
    override fun onStartListening() {
        qsTile?.apply {
            state = Tile.STATE_INACTIVE
            updateTile()
        }
    }

    override fun onClick() {
        val i = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra(MainActivity.EXTRA_CLIPBOARD, true)
        if (Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(
                PendingIntent.getActivity(this, 0, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            )
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(i)
        }
    }
}
