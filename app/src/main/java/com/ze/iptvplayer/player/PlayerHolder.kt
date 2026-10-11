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
import androidx.media3.exoplayer.DecoderCounters
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
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
 * Going fullscreen from the preview hands the SAME running player (and its live connection) over
 * to the fullscreen screen: [playChannel] is a no-op when asked for the channel already playing.
 * Switching to a DIFFERENT channel builds a brand-new ExoPlayer, and so a fresh video decoder.
 * That matters on some Android TV boxes — notably Amlogic-based ones, whose hardware decoder shows
 * up as "c2.amlogic.avc.decoder" — which don't survive ExoPlayer re-pointing one running decoder
 * at a new stream or surface: the picture freezes or playback fails with "Decoder failed".
 *
 * As a safety net for that (and for any freeze mid-viewing), a freeze detector watches whether
 * video frames are actually being drawn while playback says it's running. If they stop for a few
 * seconds, or playback errors out, the player is rebuilt and the same channel restarted, up to
 * [MAX_AUTO_RECOVERIES] times in a row. Only after those are used up is an error handed to
 * [errorListener] to show the user.
 *
 * Because the player instance can change, screens must NOT keep their own reference to it: use
 * [current] when you need it, [addListener]/[removeListener] for events (re-attached to each new
 * instance automatically), and [errorListener] for errors that couldn't be recovered.
 */
object PlayerHolder {
    private const val TAG = "PlayerHolder"

    /** How many times in a row a channel is automatically restarted before giving up. */
    private const val MAX_AUTO_RECOVERIES = 3

    /** Pause before restarting, so the old decoder is fully released before a new one is made. */
    private const val RECOVERY_DELAY_MS = 400L

    /** The freeze detector looks at the rendered-frame count this often; this many checks in a
     *  row with no new frame (while the player says it's ready and playing) counts as frozen —
     *  about 4.5 seconds. Checks that don't qualify (still buffering, paused, no video track, no
     *  screen attached) never count towards this. */
    private const val FREEZE_CHECK_INTERVAL_MS = 1_500L
    private const val FREEZE_CHECKS_BEFORE_RECOVERY = 3

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

    // Freeze detector state. videoCounters is the video renderer's own tally of drawn frames.
    private var videoCounters: DecoderCounters? = null
    private var lastRenderedCount = -1
    private var stalledChecks = 0
    private val freezeCheckRunnable = Runnable { onFreezeCheck() }

    // One shared HTTP client for every player instance, so connections/threads are reused instead
    // of rebuilt each time a channel starts. LenientTls tolerates the broken cert chains common
    // on IPTV panels (see there).
    private val httpClient: OkHttpClient by lazy { LenientTls.newClientBuilder().build() }

    private val internalListener = object : Player.Listener {
        override fun onPlayerError(error: PlaybackException) {
            Log.e(TAG, "Playback error on ${currentChannel?.streamUrl}: ${error.errorCodeName}", error)
            if (!tryAutoRecover("error ${error.errorCodeName}")) {
                errorListener?.invoke(error)
            }
        }
    }

    private val analyticsListener = object : AnalyticsListener {
        override fun onVideoEnabled(eventTime: AnalyticsListener.EventTime, decoderCounters: DecoderCounters) {
            videoCounters = decoderCounters
        }

        override fun onVideoDisabled(eventTime: AnalyticsListener.EventTime, decoderCounters: DecoderCounters) {
            if (videoCounters === decoderCounters) videoCounters = null
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
        newPlayer.addAnalyticsListener(analyticsListener)
        appListeners.forEach { newPlayer.addListener(it) }
        player = newPlayer
        boundView?.player = newPlayer
        return newPlayer
    }

    /** Stops and releases the current ExoPlayer (and its decoder) but keeps the registered
     *  listeners and the bound view, so a replacement can pick them straight back up. */
    private fun releasePlayerInstance() {
        mainHandler.removeCallbacks(freezeCheckRunnable)
        videoCounters = null
        val old = player ?: return
        player = null
        // Unbind first so the old player lets go of the video surface cleanly before release.
        boundView?.player = null
        old.release()
    }

    private fun startPlayback(channel: Channel) {
        val exoPlayer = player ?: createPlayer(appContext ?: return)
        exoPlayer.setMediaItem(MediaItem.fromUri(Uri.parse(channel.streamUrl)))
        exoPlayer.prepare()
        exoPlayer.playWhenReady = true
        armFreezeDetector()
    }

    // ---- Self-healing --------------------------------------------------------------------

    /** Forgets what the detector has seen so far, so the next check starts a fresh comparison.
     *  Used whenever the picture's destination changes (new stream, new screen's surface). */
    private fun resetFreezeBaseline() {
        lastRenderedCount = -1
        stalledChecks = 0
    }

    private fun armFreezeDetector() {
        resetFreezeBaseline()
        mainHandler.removeCallbacks(freezeCheckRunnable)
        mainHandler.postDelayed(freezeCheckRunnable, FREEZE_CHECK_INTERVAL_MS)
    }

    private fun onFreezeCheck() {
        val exoPlayer = player ?: return
        val counters = videoCounters

        // Only judge when video genuinely should be appearing: the player is ready and playing, has
        // a video track, and a screen is attached to show it on. Otherwise (buffering on a slow
        // network, paused, a radio stream, the instant between two screens) just start over.
        val shouldBeRendering = counters != null &&
            exoPlayer.playbackState == Player.STATE_READY &&
            exoPlayer.playWhenReady &&
            exoPlayer.videoFormat != null &&
            boundView != null
        if (!shouldBeRendering) {
            resetFreezeBaseline()
            mainHandler.postDelayed(freezeCheckRunnable, FREEZE_CHECK_INTERVAL_MS)
            return
        }

        counters!!.ensureUpdated()
        val rendered = counters.renderedOutputBufferCount
        when {
            lastRenderedCount < 0 -> lastRenderedCount = rendered // first look: just take a baseline
            rendered > lastRenderedCount -> {
                // Frames are flowing. Also proves the last recovery (if any) worked.
                lastRenderedCount = rendered
                stalledChecks = 0
                recoveryAttempts = 0
            }
            else -> {
                stalledChecks++
                if (stalledChecks >= FREEZE_CHECKS_BEFORE_RECOVERY) {
                    stalledChecks = 0
                    // Recovery rebuilds the player and re-arms this detector itself.
                    if (tryAutoRecover("no video frame drawn for ${FREEZE_CHECKS_BEFORE_RECOVERY * FREEZE_CHECK_INTERVAL_MS / 1000.0}s while playing")) return
                }
            }
        }
        mainHandler.postDelayed(freezeCheckRunnable, FREEZE_CHECK_INTERVAL_MS)
    }

    /** Rebuilds the player and restarts the current channel. Returns false (doing nothing) once
     *  the automatic attempts for this channel are used up. */
    private fun tryAutoRecover(reason: String): Boolean {
        val channel = currentChannel ?: return false
        if (recoveryAttempts >= MAX_AUTO_RECOVERIES) return false
        recoveryAttempts++
        Log.w(TAG, "Auto-recovering ${channel.name} (attempt $recoveryAttempts/$MAX_AUTO_RECOVERIES): $reason")
        mainHandler.removeCallbacks(freezeCheckRunnable)
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

    /** The live player right now, or null if none exists. Don't hold on to it — it can be
     *  replaced on a channel switch or an automatic recovery. */
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
     * Starts playing [channel]. If it's already the playing channel and [reuseIfSame] is true,
     * this is a no-op that keeps the existing connection (the preview -> fullscreen handoff).
     * Any other channel gets a brand-new player; see the class comment for why.
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
        val viewChanged = boundView !== playerView
        boundView = playerView
        playerView.player = player ?: createPlayer(context)
        // A different screen's surface: whether frames reach it is a fresh question.
        if (viewChanged) resetFreezeBaseline()
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
