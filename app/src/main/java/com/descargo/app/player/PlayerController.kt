package com.descargo.app.player

import android.content.ComponentName
import android.content.Context
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.descargo.app.data.HistoryItem
import com.descargo.app.engine.Kind

/** Compose-friendly wrapper around a MediaController bound to [PlaybackService]. */
class PlayerController(private val ctx: Context) {
    var controller by mutableStateOf<MediaController?>(null)
        private set
    var title by mutableStateOf<String?>(null)
        private set
    var subtitle by mutableStateOf<String?>(null)
        private set
    var artwork by mutableStateOf<String?>(null)
        private set
    var isPlaying by mutableStateOf(false)
        private set
    var isVideo by mutableStateOf(false)
        private set
    var hasMedia by mutableStateOf(false)
        private set

    private var pending: (() -> Unit)? = null
    private var connecting = false

    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) = sync(player)
    }

    /** Binds to the playback service; done lazily so app start stays light. */
    fun connect() {
        if (controller != null || connecting) return
        connecting = true
        val token = SessionToken(ctx, ComponentName(ctx, PlaybackService::class.java))
        val future = MediaController.Builder(ctx, token).buildAsync()
        future.addListener({
            connecting = false
            val c = runCatching { future.get() }.getOrNull() ?: return@addListener
            c.addListener(listener)
            controller = c
            sync(c)
            pending?.invoke()
            pending = null
        }, ContextCompat.getMainExecutor(ctx))
    }

    private fun sync(p: Player) {
        val md = p.mediaMetadata
        hasMedia = p.mediaItemCount > 0
        title = md.title?.toString()
        subtitle = md.artist?.toString()
        artwork = md.artworkUri?.toString()
        isPlaying = p.isPlaying
        isVideo = md.mediaType == MediaMetadata.MEDIA_TYPE_VIDEO
    }

    /** Plays [item] with the other playable files of [queue] around it (same kind: audio or video). */
    fun play(item: HistoryItem, queue: List<HistoryItem>) {
        val sameType = queue.filter {
            it.uri != null && it.kind != Kind.IMAGE && (it.kind == Kind.VIDEO) == (item.kind == Kind.VIDEO)
        }.ifEmpty { listOf(item) }
        val list = if (sameType.any { it.id == item.id }) sameType else listOf(item) + sameType
        val start = list.indexOfFirst { it.id == item.id }.coerceAtLeast(0)
        val media = list.map { h ->
            MediaItem.Builder()
                .setMediaId(h.id)
                .setUri(Uri.parse(h.uri))
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setTitle(h.title)
                        .setArtist(h.genre ?: h.site)
                        .setArtworkUri(h.thumbnail?.let(Uri::parse))
                        .setMediaType(if (h.kind == Kind.VIDEO) MediaMetadata.MEDIA_TYPE_VIDEO else MediaMetadata.MEDIA_TYPE_MUSIC)
                        .build()
                )
                .build()
        }
        val action = {
            controller?.run {
                setMediaItems(media, start, 0L)
                prepare()
                play()
            }
            Unit
        }
        if (controller != null) action() else {
            pending = action
            connect()
        }
    }

    fun toggle() = controller?.run { if (isPlaying) pause() else play() }
    fun next() = controller?.run { if (hasNextMediaItem()) seekToNextMediaItem() }
    fun stop() = controller?.run {
        stop()
        clearMediaItems()
    }

    fun release() {
        controller?.removeListener(listener)
        controller?.release()
        controller = null
    }
}
