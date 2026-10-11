package com.ze.iptvplayer.player

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import com.ze.iptvplayer.model.Channel
import com.ze.iptvplayer.network.LenientTls
import okhttp3.OkHttpClient
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Owns the single ExoPlayer used by both the main screen's mini-preview and the fullscreen
 * player, so only one video decoder is ever alive at a time.
 *
 * Built with the Application context (not tied to whichever Activity creates it). [attach]/[detach]
 * bind/unbind a screen's own PlayerView; [release] actually stops playback and tears the player down.
 *
 * Why every channel gets a brand-new ExoPlayer (see [playChannel]): some Android TV boxes —
 * notably Amlogic-based ones, whose hardware decoder shows up as "c2.amlogic.avc.decoder" — don't
 * survive ExoPlayer reusing one already-running decoder across a channel switch or across a change
 * of video surface. The visible symptoms are a frozen picture or a "Decoder failed" error that
 * needs the channel reloaded by hand. Starting each channel on a fresh player (and so a fresh
 * decoder) avoids that, and costs very little since the connection has to be re-opened for a new
 * stream anyway.
 *
 * As a second line of defense, a failure that still happens heals itself instead of leaving the
 * user stuck: a playback error, or a picture that never appears even though playback reports it's
 * ready, rebuilds the player and restarts the same channel, up to [MAX_AUTO_RECOVERIES] times in a
 * row. Only after those are used up is the error handed to [errorListener] to show the user.
 *
 * Because the player instance changes, screens must NOT keep their own reference to it: use
 * [current] when you need it, [addListener]/[removeListener] for events (re-attached to each new
 * instance automatically), and [errorListener] for errors that couldn't be recovered.
 */
object PlayerHolder {
    private const val TAG = "PlayerHolder"

    /** How many times in a row a channel is automatically restarted before giving up. */
    private const val MAX_AUTO_RECOVERIES = 3

    /** Pause before restarting, so the old decoder is fully released before a new one is made. */
    private const val RECOVERY_DELAY_MS = 400L

    /** How long a channel may be "ready to play" without a single frame on screen before we call
     *  the decoder stuck. Only checked while the player says it's READY — a stream that's still
     *  buffering because the network is slow is not treated as stuck. */
    private const val FIRST_FRAME_TIMEOUT_MS = 8_000L
    private const val FIRST_FRAME_RECHECK_MS = 4_000L
    private const val FIRST_FRAME_MAX_CHECKS = 6

    private var player: ExoPlayer? = null
    private var appContext: Context? = null

    /** The PlayerView currently showing the video, if any. Remembered so a replacement player
     *  can be bound to it automatically. */
    private var boundView: PlayerView? = null

    private val appListeners = CopyOnWriteArrayList<Player.Listener>()

    /** Called (on the main thread) with a playback error only once automatic recovery has given up. */
    var errorListener: ((PlaybackException) -> Unit)? = null

    var currentChannel: Channel? = null
        private set

    private val mainHandler = Handler(Looper.getMainLooper())
    private var recoveryAttempts = 0
    private var firstFrameRendered = false
    private var watchdogChecks = 0
    private val watchdogRunnable = Runnable { onWatchdogTick() }

    // One shared HTTP client for every player instance, so connections/threads are reused instead
    // of rebuilt each time a channel starts. LenientTls tolerates the broken cert chains common
    // on IPTV panels (see there).
    private val httpClient: OkHttpClient by lazy { LenientTls.newClientBuilder().build() }

    private val internalListener = object : Player.Listener {
        override fun onRenderedFirstFrame() {
            firstFrameRendered = true
            recoveryAttempts = 0
            mainHandler.removeCallbacks(watchdogRunnable)
        }

        override fun onPlayerError(error: PlaybackException) {
            Log.e(TAG, "Playback error on ${currentChannel?.streamUrl}: ${error.errorCodeName}", error)
            if (!tryAutoRecover("error ${error.errorCodeName}")) {
                errorListener?.invoke(error)
            }
        }
    }

    private fun createPlayer(context: Context): ExoPlayer {
        val ctx = context.applicationContext
        appContext = ctx

        val dataSourceFactory = OkHttpDataSource.Factory(httpClient)
            .setUserAgent("Mozilla/5.0 (Linux; Android TV) IptvPlayer/1.0")
        // Tighter buffer durations trade a little rebuffering risk for a faster start.
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                DefaultLoadControl.DEFAULT_MIN_BUFFER_MS,
                DefaultLoadControl.DEFAULT_MAX_BUFFER_MS,
                1000,
                2000
            )
            .build()
        // If a device's preferred (hardware) decoder can't be initialised, fall back to the next
        // one that can — e.g. the software decoder — rather than failing playback outright.
        val renderersFactory = DefaultRenderersFactory(ctx).setEnableDecoderFallback(true)

        val newPlayer = ExoPlayer.Builder(ctx, renderersFactory)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory))
            .setLoadControl(loadControl)
            .build()
        newPlayer.addListener(internalListener)
        appListeners.forEach { newPlayer.addListener(it) }
        player = newPlayer
        boundView?.player = newPlayer
        return newPlayer
    }

    /** Stops and releases the current ExoPlayer (and its decoder) but keeps the registered
     *  listeners and the bound view, so a replacement can pick them straight back up. */
    private fun releasePlayerInstance() {
        mainHandler.removeCallbacks(watchdogRunnable)
        val old = player ?: return
        player = null
        // Unbind first so the old player lets go of the video surface cleanly before release.
        boundView?.player = null
        old.release()
    }

    private fun startPlayback(channel: Channel) {
        val exoPlayer = player ?: createPlayer(appContext ?: return)
        firstFrameRendered = false
        exoPlayer.setMediaItem(MediaItem.fromUri(Uri.parse(channel.streamUrl)))
        exoPlayer.prepare()
        exoPlayer.playWhenReady = true
        armWatchdog()
    }

    // ---- Self-healing --------------------------------------------------------------------

    private fun armWatchdog() {
        mainHandler.removeCallbacks(watchdogRunnable)
        watchdogChecks = 0
        mainHandler.postDelayed(watchdogRunnable, FIRST_FRAME_TIMEOUT_MS)
    }

    private fun onWatchdogTick() {
        val exoPlayer = player ?: return
        if (firstFrameRendered) return
        // "Stuck" means: the player says it's ready and has a video track, a screen is attached to
        // show it on — and yet no frame has ever appeared. Still buffering (slow network) or a
        // stream with no video at all (radio) is not stuck, so those are left alone.
        val stuck = exoPlayer.playbackState == Player.STATE_READY &&
            exoPlayer.videoFormat != null &&
            boundView != null
        if (stuck) {
            tryAutoRecover("ready but no video frame after ${FIRST_FRAME_TIMEOUT_MS / 1000}s")
            return
        }
        watchdogChecks++
        if (watchdogChecks < FIRST_FRAME_MAX_CHECKS) {
            mainHandler.postDelayed(watchdogRunnable, FIRST_FRAME_RECHECK_MS)
        }
    }

    /** Rebuilds the player and restarts the current channel. Returns false (doing nothing) once
     *  the automatic attempts for this channel are used up. */
    private fun tryAutoRecover(reason: String): Boolean {
        val channel = currentChannel ?: return false
        if (recoveryAttempts >= MAX_AUTO_RECOVERIES) return false
        recoveryAttempts++
        Log.w(TAG, "Auto-recovering ${channel.name} (attempt $recoveryAttempts/$MAX_AUTO_RECOVERIES): $reason")
        mainHandler.removeCallbacks(watchdogRunnable)
        mainHandler.postDelayed({
            // The user may have switched channel or left the player while we waited.
            if (currentChannel?.key != channel.key) return@postDelayed
            val ctx = appContext ?: return@postDelayed
            releasePlayerInstance()
            createPlayer(ctx)
            startPlayback(channel)
        }, RECOVERY_DELAY_MS)
        return true
    }

    // ---- Public API ----------------------------------------------------------------------

    /** The live player right now, or null if none exists. Don't hold on to it — it is replaced on
     *  every channel switch. */
    fun current(): ExoPlayer? = player

    /** Registers [listener] on the current player and on every replacement created later. */
    fun addListener(listener: Player.Listener) {
        if (appListeners.addIfAbsent(listener)) player?.addListener(listener)
    }

    fun removeListener(listener: Player.Listener) {
        appListeners.remove(listener)
        player?.removeListener(listener)
    }

    /** Returns the player, creating it if none exists yet. */
    fun ensurePlayer(context: Context): ExoPlayer = player ?: createPlayer(context)

    /**
     * Starts playing [channel]. Normally a different channel (or [reuseIfSame] = false) gets a
     * brand-new player; see the class comment for why. With [reuseIfSame] = true, asking for the
     * channel that's already playing is a no-op that keeps the existing connection.
     */
    fun playChannel(context: Context, channel: Channel, reuseIfSame: Boolean = true) {
        appContext = context.applicationContext
        if (reuseIfSame && player != null && currentChannel?.key == channel.key) return
        currentChannel = channel
        recoveryAttempts = 0
        releasePlayerInstance()
        createPlayer(context)
        startPlayback(channel)
    }

    /** Binds the player to [playerView] so it renders there. */
    fun attach(context: Context, playerView: PlayerView) {
        appContext = context.applicationContext
        boundView = playerView
        playerView.player = player ?: createPlayer(context)
    }

    /** Unbinds the player from [playerView] WITHOUT stopping playback. */
    fun detach(playerView: PlayerView) {
        if (boundView === playerView) boundView = null
        if (playerView.player === player) playerView.player = null
    }

    /** Actually stops playback and tears the player down. */
    fun release() {
        releasePlayerInstance()
        boundView = null
        currentChannel = null
        recoveryAttempts = 0
    }
}
