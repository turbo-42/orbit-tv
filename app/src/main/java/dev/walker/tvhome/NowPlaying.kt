package dev.walker.tvhome

import android.content.ComponentName
import android.content.Context
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.service.notification.NotificationListenerService
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext

/**
 * Exists only so Android lets this app read active media sessions; it handles no notifications.
 * Enabled with `adb shell cmd notification allow_listener dev.walker.tvhome/dev.walker.tvhome.MediaListenerService`.
 */
class MediaListenerService : NotificationListenerService()

private const val HIDE_AFTER_MS = 1_500L

/** In-between states around play, seek and skip; treated as playing so the badge doesn't flicker. */
private val ACTIVE_STATES = setOf(
    PlaybackState.STATE_PLAYING,
    PlaybackState.STATE_BUFFERING,
    PlaybackState.STATE_CONNECTING,
    PlaybackState.STATE_SKIPPING_TO_NEXT,
    PlaybackState.STATE_SKIPPING_TO_PREVIOUS,
    PlaybackState.STATE_SKIPPING_TO_QUEUE_ITEM,
    PlaybackState.STATE_FAST_FORWARDING,
    PlaybackState.STATE_REWINDING,
)

data class NowPlaying(
    val packageName: String,
    val title: String,
    val subtitle: String,
    /** Bitmap art when the session provides one; otherwise [artUri] may point at it. */
    val art: ImageBitmap?,
    val artUri: String?,
    val isPlaying: Boolean,
    private val controls: MediaController.TransportControls,
) {
    fun togglePlayPause() = if (isPlaying) controls.pause() else controls.play()

    fun skipNext() = controls.skipToNext()
}

/**
 * The most relevant active media session (playing beats paused), or null when nothing is playing.
 * Re-subscribes on each resume, so access granted while the screen was running is picked up.
 */
@Composable
fun rememberNowPlaying(resumeCount: Int): NowPlaying? {
    val context = LocalContext.current
    val watcher = remember { NowPlayingWatcher(context) }
    DisposableEffect(watcher, resumeCount) {
        watcher.start()
        onDispose { watcher.stop() }
    }
    return watcher.current
}

private class NowPlayingWatcher(private val context: Context) {

    var current by mutableStateOf<NowPlaying?>(null)
        private set

    private val sessions = context.getSystemService(MediaSessionManager::class.java)
    private val listener = ComponentName(context, MediaListenerService::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private var controllers = emptyList<MediaController>()

    private val controllerCallback = object : MediaController.Callback() {
        override fun onPlaybackStateChanged(state: PlaybackState?) = refresh()
        override fun onMetadataChanged(metadata: MediaMetadata?) = refresh()
        override fun onSessionDestroyed() = refresh()
    }

    private val sessionsListener = MediaSessionManager.OnActiveSessionsChangedListener { active ->
        watch(active.orEmpty())
    }

    fun start() {
        // Throws SecurityException until the listener is allowed; the card simply never appears.
        runCatching {
            sessions.addOnActiveSessionsChangedListener(sessionsListener, listener, handler)
            watch(sessions.getActiveSessions(listener))
        }
    }

    fun stop() {
        handler.removeCallbacks(clearCurrent)
        runCatching { sessions.removeOnActiveSessionsChangedListener(sessionsListener) }
        controllers.forEach { it.unregisterCallback(controllerCallback) }
        controllers = emptyList()
    }

    private fun watch(active: List<MediaController>) {
        controllers.forEach { it.unregisterCallback(controllerCallback) }
        controllers = active.filter { it.packageName != context.packageName }
        controllers.forEach { it.registerCallback(controllerCallback, handler) }
        refresh()
    }

    private val clearCurrent = Runnable { current = null }

    private fun refresh() {
        val next = controllers
            .mapNotNull { it.toNowPlaying() }
            .sortedByDescending { it.isPlaying }
            .firstOrNull()
        // Sessions drop out briefly around track changes; only hide once nothing returns.
        handler.removeCallbacks(clearCurrent)
        if (next != null || current == null) current = next else handler.postDelayed(clearCurrent, HIDE_AFTER_MS)
    }

    private fun MediaController.toNowPlaying(): NowPlaying? {
        val state = playbackState?.state ?: return null
        val isPlaying = state in ACTIVE_STATES
        if (!isPlaying && state != PlaybackState.STATE_PAUSED) return null
        val meta = metadata ?: return null
        val title = meta.getString(MediaMetadata.METADATA_KEY_TITLE)
            ?: meta.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE)
            ?: return null
        val subtitle = meta.getString(MediaMetadata.METADATA_KEY_ARTIST)
            ?: meta.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE)
            ?: meta.getString(MediaMetadata.METADATA_KEY_ALBUM)
            ?: ""
        // Spotify republishes metadata (e.g. on seek or queue changes) before its art has loaded,
        // sometimes with only DISPLAY_ICON, its app logo. Keep this track's last real artwork
        // and use the display icon only for tracks that never supply any.
        val key = "$packageName|$title|$subtitle"
        val albumArt = meta.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
            ?: meta.getBitmap(MediaMetadata.METADATA_KEY_ART)
        val albumArtUri = meta.getString(MediaMetadata.METADATA_KEY_ALBUM_ART_URI)
            ?: meta.getString(MediaMetadata.METADATA_KEY_ART_URI)
        if (albumArt != null || albumArtUri != null) {
            artCache[key] = Artwork(albumArt?.asImageBitmap(), albumArtUri)
        }
        if (albumArt != null || albumArtUri != null) lastArtByPackage[packageName] = artCache.getValue(key)
        // A new track's art often lands a moment after its title: keep the previous cover until then.
        val artwork = artCache[key] ?: lastArtByPackage[packageName] ?: Artwork(
            meta.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)?.asImageBitmap(),
            meta.getString(MediaMetadata.METADATA_KEY_DISPLAY_ICON_URI),
        )
        return NowPlaying(packageName, title, subtitle, artwork.bitmap, artwork.uri, isPlaying, transportControls)
    }

    private data class Artwork(val bitmap: ImageBitmap?, val uri: String?)

    private val lastArtByPackage = mutableMapOf<String, Artwork>()

    /** Last real artwork per track; small, and bounded so a long listening session can't grow it. */
    private val artCache = object : LinkedHashMap<String, Artwork>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Artwork>?) = size > 8
    }
}
