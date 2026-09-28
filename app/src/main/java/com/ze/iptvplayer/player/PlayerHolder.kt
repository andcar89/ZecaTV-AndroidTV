package com.ze.iptvplayer.player

import android.content.Context
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import com.ze.iptvplayer.model.Channel
import com.ze.iptvplayer.network.LenientTls

/**
 * Owns a single ExoPlayer instance shared between the main screen's mini-preview and the
 * fullscreen player. Previously, each screen built and owned its own ExoPlayer — going fullscreen
 * after previewing a channel meant tearing down a perfectly good, already-buffering connection
 * and starting a brand new one from scratch, which is what made that transition feel slow.
 *
 * Now, [playChannel] is a no-op if the shared player is already playing that exact channel — so
 * MainActivity previewing channel X, then the user opening it fullscreen, hands the SAME live
 * connection over to PlayerActivity instead of restarting it. Switching to a genuinely different
 * channel (from either screen) still tears down and reconnects as before — there's no buffered
 * state worth keeping in that case anyway.
 *
 * Built with the Application context (not tied to whichever Activity happens to create it), since
 * it's meant to outlive any single screen. [attach]/[detach] bind/unbind a screen's own PlayerView
 * without touching the underlying player or its playback state; only [release] actually stops
 * playback and tears the player down.
 */
object PlayerHolder {
    private var player: ExoPlayer? = null

    var currentChannel: Channel? = null
        private set

    private fun getOrCreate(context: Context): ExoPlayer {
        player?.let { return it }

        // Same setup PlayerActivity/MainActivity each used to build individually — see there for
        // why: LenientTls tolerates the broken cert chains common on IPTV panels, and the tighter
        // buffer durations trade a little rebuffering risk for a faster start.
        val dataSourceFactory = OkHttpDataSource.Factory(LenientTls.newClientBuilder().build())
            .setUserAgent("Mozilla/5.0 (Linux; Android TV) IptvPlayer/1.0")
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                DefaultLoadControl.DEFAULT_MIN_BUFFER_MS,
                DefaultLoadControl.DEFAULT_MAX_BUFFER_MS,
                1000,
                2000
            )
            .build()

        val newPlayer = ExoPlayer.Builder(context.applicationContext)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory))
            .setLoadControl(loadControl)
            .build()
        player = newPlayer
        return newPlayer
    }

    /** Returns the shared player, creating it if it doesn't exist yet — for attaching a
     *  Player.Listener before any playback starts. */
    fun ensurePlayer(context: Context): ExoPlayer = getOrCreate(context)

    /** Starts playing [channel] on the shared player. If it's already playing this exact channel
     *  (matched by [Channel.key], the stream URL — see Channel's own docs on why not [Channel.id]),
     *  this is a no-op, preserving whatever connection/buffer already exists. */
    fun playChannel(context: Context, channel: Channel) {
        val exoPlayer = getOrCreate(context)
        if (currentChannel?.key == channel.key) return
        currentChannel = channel
        exoPlayer.setMediaItem(MediaItem.fromUri(Uri.parse(channel.streamUrl)))
        exoPlayer.prepare()
        exoPlayer.playWhenReady = true
    }

    /** Binds the shared player to [playerView] so it renders there. */
    fun attach(context: Context, playerView: PlayerView) {
        playerView.player = getOrCreate(context)
    }

    /** Unbinds the shared player from [playerView] WITHOUT stopping playback — for handing off
     *  to a different screen's PlayerView, not for actually ending playback (see [release]). */
    fun detach(playerView: PlayerView) {
        if (playerView.player === player) playerView.player = null
    }

    /** Actually stops playback and tears the shared player down. */
    fun release() {
        player?.release()
        player = null
        currentChannel = null
    }
}
