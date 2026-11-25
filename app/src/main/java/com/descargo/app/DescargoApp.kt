package com.descargo.app

import android.app.Application
import com.descargo.app.data.AppSettings
import com.descargo.app.data.HistoryStore
import com.descargo.app.data.SubscriptionStore
import com.descargo.app.engine.Engine
import com.descargo.app.work.DownloadWorker
import com.descargo.app.work.SubscriptionWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class DescargoApp : Application() {
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        AppSettings.init(this)
        HistoryStore.init(this)
        HistoryStore.onChange = { com.descargo.app.ui.DescargoWidget.refresh(this) }
        SubscriptionStore.init(this)
        DownloadWorker.createChannels(this)
        SubscriptionWorker.schedule(this)
        // Unpacks python/yt-dlp/ffmpeg on first run, then refreshes yt-dlp at most every 12 h.
        appScope.launch { Engine.start(this@DescargoApp) }
    }
}
