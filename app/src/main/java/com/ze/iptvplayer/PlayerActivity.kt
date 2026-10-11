package com.ze.iptvplayer

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.ze.iptvplayer.data.FavoritesManager
import com.ze.iptvplayer.data.PlaylistRepository
import com.ze.iptvplayer.model.Channel
import com.ze.iptvplayer.model.ContentType
import com.ze.iptvplayer.player.PlayerHolder
import com.ze.iptvplayer.ui.CategoryEntry
import com.ze.iptvplayer.ui.ChannelListAdapter
import com.ze.iptvplayer.ui.SidebarCategoryAdapter
import java.text.SimpleDateFormat
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Fullscreen playback. Controls differ depending on [isVod] — false for a live channel, true for
 * a movie/TV show episode (see ContentTypeClassifier) — since a VOD stream has no "other channels
 * in this category" to switch between and does have a timeline worth scrubbing:
 *
 * LIVE:
 *  - LEFT toggles a sidebar listing every channel in the active category, for switching
 *    channel without leaving fullscreen.
 *  - RIGHT toggles a sidebar of categories; picking one updates the LEFT sidebar's list.
 *  - DOWN (when nothing else is open) shows a channel info bar that auto-hides.
 * VOD (movie/episode):
 *  - LEFT/RIGHT seek back/forward 10s.
 *  - DPAD_CENTER/ENTER toggles play/pause.
 *  - DOWN shows a scrub bar (position, duration, play/pause indicator) that auto-hides.
 * Both:
 *  - UP opens a sidebar of Audio/Subtitle tracks reported by the player.
 *  - MENU hands playback off to an external player app.
 * Switching channel keeps this screen (and its sidebars) open rather than recreating
 * PlayerActivity each time; the video itself is started on a fresh player each time, owned by
 * PlayerHolder, which also restarts playback by itself if the decoder fails or freezes.
 */
class PlayerActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_CHANNEL_ID = "extra_channel_id"
        const val EXTRA_CATEGORY_ID = "extra_category_id"
        private const val INFO_BAR_AUTO_HIDE_MS = 6000L
    }

    // Always read through the holder: it builds a brand-new player for every channel (see
    // PlayerHolder), so a reference kept here would go stale — and be released — on the next zap.
    private val player: ExoPlayer? get() = PlayerHolder.current()
    private lateinit var playerView: PlayerView

    private lateinit var channelSidebar: View
    private lateinit var channelSidebarTitle: TextView
    private lateinit var channelSidebarRecycler: RecyclerView
    private lateinit var categorySidebar: View
    private lateinit var categorySidebarRecycler: RecyclerView
    private lateinit var tracksSidebar: View
    private lateinit var audioTracksRecycler: RecyclerView
    private lateinit var subtitleTracksRecycler: RecyclerView
    private lateinit var vodControls: View
    private lateinit var vodTitleText: TextView
    private lateinit var vodPlayPauseIcon: ImageView
    private lateinit var vodPositionText: TextView
    private lateinit var vodDurationText: TextView
    private lateinit var vodProgressBar: ProgressBar
    private lateinit var infoBar: View
    private lateinit var infoBarLogo: ImageView
    private lateinit var infoBarName: TextView
    private lateinit var infoBarNow: TextView
    private lateinit var infoBarNext: TextView
    private lateinit var infoBarClock: TextView
    private lateinit var infoBarResolution: TextView
    private lateinit var infoBarBitrate: TextView

    private lateinit var channelAdapter: ChannelListAdapter
    private lateinit var categoryAdapter: SidebarCategoryAdapter
    private lateinit var audioTrackAdapter: SidebarCategoryAdapter
    private lateinit var subtitleTrackAdapter: SidebarCategoryAdapter

    private var currentChannel: Channel? = null
    private var currentCategoryId: String = PlaylistRepository.CATEGORY_ALL

    /** True for a movie or TV show episode (see ContentTypeClassifier) — switches the fullscreen
     *  player from "live channel" controls (LEFT/RIGHT = channel/category sidebars) to "VOD"
     *  controls (LEFT/RIGHT = seek, DPAD_CENTER = play/pause, a scrub bar instead of the info bar). */
    private var isVod = false

    private data class TrackOption(
        val id: String,
        val label: String,
        val group: Tracks.Group?,
        val trackIndexInGroup: Int
    )
    private var audioTrackOptions: List<TrackOption> = emptyList()
    private var subtitleTrackOptions: List<TrackOption> = emptyList()

    private val hideInfoBarHandler = Handler(Looper.getMainLooper())
    private val hideInfoBarRunnable = Runnable { infoBar.visibility = View.GONE }

    private val hideVodControlsHandler = Handler(Looper.getMainLooper())
    private val hideVodControlsRunnable = Runnable { hideVodControls() }

    // Ticks while the VOD scrub bar is visible, pulling the live position straight off the
    // player rather than tracking it separately — cheap enough at a 500ms interval.
    private val vodProgressHandler = Handler(Looper.getMainLooper())
    private val vodProgressRunnable = object : Runnable {
        override fun run() {
            updateVodProgressUi()
            vodProgressHandler.postDelayed(this, 500L)
        }
    }

    // If playback stays in STATE_BUFFERING for this long without recovering on its own, force a
    // fresh connection instead of leaving the user stuck on a frozen frame needing to back out
    // and reopen the channel manually.
    private val stallRecoveryHandler = Handler(Looper.getMainLooper())
    private var stallRecoveryRunnable: Runnable? = null

    // Ticks continuously (not just while the info bar is visible) so the clock is already
    // current the moment the bar is shown, rather than needing a separate show/hide-coordinated
    // start/stop — a once-a-second text update is cheap enough to just leave running.
    private val clockHandler = Handler(Looper.getMainLooper())
    private val clockRunnable = object : Runnable {
        override fun run() {
            infoBarClock.text = SimpleDateFormat("HH:mm", Locale.getDefault()).format(System.currentTimeMillis())
            clockHandler.postDelayed(this, 1000L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Video actively playing doesn't count as "user activity" to the system — without this,
        // Android TV's idle timeout (screensaver / standby) still kicks in after its usual ~10
        // minutes even mid-playback, since no remote button has been pressed in that time.
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_player)

        playerView = findViewById(R.id.player_view)
        channelSidebar = findViewById(R.id.channel_sidebar)
        channelSidebarTitle = findViewById(R.id.channel_sidebar_title)
        channelSidebarRecycler = findViewById(R.id.channel_sidebar_recycler)
        categorySidebar = findViewById(R.id.category_sidebar)
        categorySidebarRecycler = findViewById(R.id.category_sidebar_recycler)
        tracksSidebar = findViewById(R.id.tracks_sidebar)
        audioTracksRecycler = findViewById(R.id.audio_tracks_recycler)
        subtitleTracksRecycler = findViewById(R.id.subtitle_tracks_recycler)
        vodControls = findViewById(R.id.vod_controls)
        vodTitleText = findViewById(R.id.vod_title_text)
        vodPlayPauseIcon = findViewById(R.id.vod_play_pause_icon)
        vodPositionText = findViewById(R.id.vod_position_text)
        vodDurationText = findViewById(R.id.vod_duration_text)
        vodProgressBar = findViewById(R.id.vod_progress_bar)
        infoBar = findViewById(R.id.info_bar)
        infoBarLogo = findViewById(R.id.info_bar_logo)
        infoBarName = findViewById(R.id.info_bar_name)
        infoBarNow = findViewById(R.id.info_bar_now)
        infoBarNext = findViewById(R.id.info_bar_next)
        infoBarClock = findViewById(R.id.info_bar_clock)
        infoBarResolution = findViewById(R.id.info_bar_resolution)
        infoBarBitrate = findViewById(R.id.info_bar_bitrate)

        currentCategoryId = intent.getStringExtra(EXTRA_CATEGORY_ID) ?: PlaylistRepository.CATEGORY_ALL
        val startChannelKey = intent.getStringExtra(EXTRA_CHANNEL_ID)
        val startChannel = PlaylistRepository.channels.firstOrNull { it.key == startChannelKey }

        if (startChannel == null) {
            Toast.makeText(this, getString(R.string.error_channel_not_found), Toast.LENGTH_LONG).show()
            finish()
            return
        }

        isVod = startChannel.contentType != ContentType.LIVE
        setupSidebars()
        setupTracksSidebar()
        // reuseIfSame = false: even if the main screen's preview was already playing this exact
        // channel, start it on a fresh player here instead of re-pointing the preview's running
        // decoder at this screen's surface — that handoff is what froze fullscreen on some boxes.
        // Done BEFORE attaching this screen's view, so the old decoder is never bound to it.
        playChannel(startChannel, reuseIfSame = false)
        initializePlayer()
        clockHandler.post(clockRunnable)
    }

    private fun setupTracksSidebar() {
        audioTrackAdapter = SidebarCategoryAdapter(emptyList(), null) { entry -> applyAudioTrackSelection(entry.id) }
        audioTracksRecycler.layoutManager = LinearLayoutManager(this)
        audioTracksRecycler.adapter = audioTrackAdapter
        audioTracksRecycler.itemAnimator = null

        subtitleTrackAdapter = SidebarCategoryAdapter(emptyList(), null) { entry -> applySubtitleTrackSelection(entry.id) }
        subtitleTracksRecycler.layoutManager = LinearLayoutManager(this)
        subtitleTracksRecycler.adapter = subtitleTrackAdapter
        subtitleTracksRecycler.itemAnimator = null
    }

    private fun setupSidebars() {
        channelAdapter = ChannelListAdapter(
            context = this,
            channels = PlaylistRepository.channelsForCategory(this, currentCategoryId),
            isGrid = false,
            onClick = { channel ->
                playChannel(channel)
                hideAllOverlays()
            },
            onLongClick = { channel, _ ->
                val nowFavorite = FavoritesManager.toggle(this, channel.key)
                Toast.makeText(
                    this,
                    if (nowFavorite) getString(R.string.toast_added_to_favorites) else getString(R.string.toast_removed_from_favorites),
                    Toast.LENGTH_SHORT
                ).show()
                val visibleChannels = PlaylistRepository.channelsForCategory(this, currentCategoryId)
                if (currentCategoryId == PlaylistRepository.CATEGORY_FAVORITES) {
                    // The Favorites list itself just gained/lost an entry — it has to be rebuilt.
                    channelAdapter.submitList(visibleChannels)
                } else {
                    // Any other category has the same channels as before; only this row's star
                    // changed. Re-bind just that row instead of the whole list — every row bind
                    // does an EPG lookup and a logo load, which is real work on a TV box's CPU.
                    val position = visibleChannels.indexOfFirst { it.key == channel.key }
                    if (position >= 0) channelAdapter.notifyItemChanged(position)
                }
            },
            // Matches channel_sidebar's own background (activity_player.xml) rather than the
            // layout's default background_color, which is meant for MainActivity's own background.
            logoBackgroundColor = android.graphics.Color.parseColor("#1A1D24")
        )
        channelSidebarRecycler.layoutManager = LinearLayoutManager(this).apply { initialPrefetchItemCount = 8 }
        channelSidebarRecycler.adapter = channelAdapter
        // Same reasoning as MainActivity's channel list — see there for details.
        channelSidebarRecycler.setItemViewCacheSize(20)
        channelSidebarRecycler.itemAnimator = null
        updateChannelSidebarTitle()

        val categoryEntries = PlaylistRepository.categories(this).map { (id, label) -> CategoryEntry(id, label) }
        categoryAdapter = SidebarCategoryAdapter(categoryEntries, currentCategoryId) { entry ->
            currentCategoryId = entry.id
            updateChannelSidebarTitle()
            channelAdapter.submitList(PlaylistRepository.channelsForCategory(this, entry.id))
            showChannelSidebar()
        }
        categorySidebarRecycler.layoutManager = LinearLayoutManager(this)
        categorySidebarRecycler.adapter = categoryAdapter
        categorySidebarRecycler.itemAnimator = null
    }

    private fun updateChannelSidebarTitle() {
        channelSidebarTitle.text = when (currentCategoryId) {
            PlaylistRepository.CATEGORY_FAVORITES -> getString(R.string.category_favorites)
            PlaylistRepository.CATEGORY_ALL -> getString(R.string.category_all_channels)
            Channel.UNGROUPED_GROUP_KEY -> getString(R.string.category_uncategorized)
            else -> currentCategoryId
        }
    }

    // ---- Channel switching ---------------------------------------------------

    private fun playChannel(channel: Channel, reuseIfSame: Boolean = true) {
        currentChannel = channel
        isVod = channel.contentType != ContentType.LIVE
        PlayerHolder.playChannel(this, channel, reuseIfSame)
        if (isVod) {
            showVodControls()
        } else {
            updateInfoBarContent()
        }
    }

    // Registered with PlayerHolder (not directly on an ExoPlayer) so it's re-attached automatically
    // to each replacement player the holder builds on a channel switch or an automatic recovery.
    private val playerListener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            stallRecoveryRunnable?.let { stallRecoveryHandler.removeCallbacks(it) }
            stallRecoveryRunnable = null
            if (playbackState == Player.STATE_BUFFERING) {
                val channelAtStall = currentChannel
                val runnable = Runnable {
                    Log.w("PlayerActivity", "Playback stalled >15s for ${channelAtStall?.streamUrl}, re-preparing")
                    player?.prepare()
                }
                stallRecoveryRunnable = runnable
                stallRecoveryHandler.postDelayed(runnable, 15_000L)
            }
        }

        override fun onTracksChanged(tracks: Tracks) {
            // Resolution/bitrate aren't known immediately on channel switch — only once
            // track selection actually completes. Refresh if the bar's already showing.
            if (infoBar.visibility == View.VISIBLE) updateInfoBarContent()
            refreshTrackOptions(tracks)
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            vodPlayPauseIcon.setImageResource(if (isPlaying) R.drawable.ic_player_pause else R.drawable.ic_player_play)
        }
    }

    private fun initializePlayer() {
        // The player itself lives in PlayerHolder. Errors reach us only once its automatic
        // recovery (rebuild the player, restart the channel) has been tried and failed.
        PlayerHolder.attach(this, playerView)
        PlayerHolder.addListener(playerListener)
        PlayerHolder.errorListener = { error: PlaybackException ->
            val cause = error.cause?.message ?: error.message ?: getString(R.string.error_unknown)
            Log.e("PlayerActivity", "Playback failed for ${currentChannel?.streamUrl}", error)
            Toast.makeText(this@PlayerActivity, getString(R.string.toast_playback_failed, cause), Toast.LENGTH_LONG).show()
        }
    }

    // ---- Audio/subtitle tracks ------------------------------------------------

    /** Rebuilds the Audio/Subtitles lists from whatever the player currently reports. Cheap
     *  enough to call on every onTracksChanged regardless of whether the sidebar is open, so it's
     *  always current the moment the user does open it. */
    private fun refreshTrackOptions(tracks: Tracks) {
        val audio = mutableListOf<TrackOption>()
        val subs = mutableListOf<TrackOption>()
        var audioIndex = 0
        var subIndex = 0
        for (group in tracks.groups) {
            when (group.type) {
                C.TRACK_TYPE_AUDIO -> {
                    for (i in 0 until group.length) {
                        if (!group.isTrackSupported(i)) continue
                        audioIndex++
                        audio.add(TrackOption("a$audioIndex", trackLabel(group.getTrackFormat(i), audioIndex), group, i))
                    }
                }
                C.TRACK_TYPE_TEXT -> {
                    for (i in 0 until group.length) {
                        if (!group.isTrackSupported(i)) continue
                        subIndex++
                        subs.add(TrackOption("s$subIndex", trackLabel(group.getTrackFormat(i), subIndex), group, i))
                    }
                }
                else -> {}
            }
        }
        audioTrackOptions = audio
        subtitleTrackOptions = listOf(TrackOption("off", getString(R.string.label_subtitles_off), null, -1)) + subs

        val textDisabled = player?.trackSelectionParameters?.disabledTrackTypes?.contains(C.TRACK_TYPE_TEXT) == true
        val selectedAudioId = audio.firstOrNull { it.group?.isTrackSelected(it.trackIndexInGroup) == true }?.id
        val selectedSubId = if (textDisabled) {
            "off"
        } else {
            subs.firstOrNull { it.group?.isTrackSelected(it.trackIndexInGroup) == true }?.id ?: "off"
        }

        audioTrackAdapter.updateCategories(audio.map { CategoryEntry(it.id, it.label) }, selectedAudioId)
        subtitleTrackAdapter.updateCategories(subtitleTrackOptions.map { CategoryEntry(it.id, it.label) }, selectedSubId)
    }

    private fun trackLabel(format: Format, indexInType: Int): String {
        val name = format.label?.takeIf { it.isNotBlank() }
        if (name != null) return name
        val lang = format.language?.takeIf { it.isNotBlank() && it != "und" }
        if (lang != null) return lang.uppercase(Locale.getDefault())
        return getString(R.string.label_track_unnamed, indexInType)
    }

    private fun applyAudioTrackSelection(id: String) {
        val option = audioTrackOptions.firstOrNull { it.id == id } ?: return
        val group = option.group ?: return
        val exoPlayer = player ?: return
        exoPlayer.trackSelectionParameters = exoPlayer.trackSelectionParameters.buildUpon()
            .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, option.trackIndexInGroup))
            .build()
    }

    private fun applySubtitleTrackSelection(id: String) {
        val exoPlayer = player ?: return
        val builder = exoPlayer.trackSelectionParameters.buildUpon()
        if (id == "off") {
            builder.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
        } else {
            val option = subtitleTrackOptions.firstOrNull { it.id == id }
            val group = option?.group ?: return
            builder.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
            builder.setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, option.trackIndexInGroup))
        }
        exoPlayer.trackSelectionParameters = builder.build()
    }

    // ---- Info bar --------------------------------------------------------

    private fun updateInfoBarContent() {
        val channel = currentChannel ?: return
        infoBarName.text = channel.name
        if (!channel.logoUrl.isNullOrBlank()) {
            Glide.with(this).load(channel.logoUrl)
                .placeholder(R.drawable.default_channel_logo)
                .error(R.drawable.default_channel_logo)
                .into(infoBarLogo)
        } else {
            Glide.with(this).clear(infoBarLogo)
            infoBarLogo.setImageResource(R.drawable.default_channel_logo)
        }
        val fmt = SimpleDateFormat("HH:mm", Locale.getDefault())
        val (current, next) = PlaylistRepository.nowNext(this, channel)
        if (current != null) {
            infoBarNow.text = "${fmt.format(current.startMillis)}–${fmt.format(current.stopMillis)}  ${current.title}"
            infoBarNow.visibility = View.VISIBLE
        } else {
            infoBarNow.visibility = View.GONE
        }
        if (next != null) {
            infoBarNext.text = "${fmt.format(next.startMillis)}  ${next.title}"
            infoBarNext.visibility = View.VISIBLE
        } else {
            infoBarNext.visibility = View.GONE
        }

        val format = player?.videoFormat
        if (format != null && format.width > 0 && format.height > 0) {
            infoBarResolution.text = "${format.width}×${format.height}"
            infoBarResolution.visibility = View.VISIBLE
        } else {
            infoBarResolution.visibility = View.GONE
        }
        if (format != null && format.bitrate != Format.NO_VALUE && format.bitrate > 0) {
            infoBarBitrate.text = "${format.bitrate / 1000} kbps"
            infoBarBitrate.visibility = View.VISIBLE
        } else {
            infoBarBitrate.visibility = View.GONE
        }
    }

    private fun showInfoBar() {
        updateInfoBarContent()
        infoBar.visibility = View.VISIBLE
        hideInfoBarHandler.removeCallbacks(hideInfoBarRunnable)
        hideInfoBarHandler.postDelayed(hideInfoBarRunnable, INFO_BAR_AUTO_HIDE_MS)
    }

    private fun hideInfoBar() {
        hideInfoBarHandler.removeCallbacks(hideInfoBarRunnable)
        infoBar.visibility = View.GONE
    }

    // ---- Sidebars ----------------------------------------------------------

    private fun showChannelSidebar() {
        hideInfoBar()
        channelSidebar.visibility = View.VISIBLE
        val index = PlaylistRepository.channelsForCategory(this, currentCategoryId)
            .indexOfFirst { it.key == currentChannel?.key }
        val layoutManager = channelSidebarRecycler.layoutManager as? LinearLayoutManager
        // Scroll to the target position FIRST. findViewByPosition() below only returns a view
        // that's actually attached/rendered — on the very first open, the RecyclerView hasn't
        // scrolled anywhere yet, so a mid-list channel's row wouldn't be attached and this would
        // silently fall back to focusing item 0 (the wrong channel) instead.
        if (index >= 0) layoutManager?.scrollToPositionWithOffset(index, 0)
        channelSidebarRecycler.post {
            val target = if (index >= 0) layoutManager?.findViewByPosition(index) else null
            (target ?: channelSidebarRecycler.getChildAt(0))?.requestFocus()
        }
    }

    private fun hideChannelSidebar() {
        channelSidebar.visibility = View.GONE
    }

    private fun showCategorySidebar() {
        hideInfoBar()
        categorySidebar.visibility = View.VISIBLE
        categorySidebarRecycler.post { categorySidebarRecycler.getChildAt(0)?.requestFocus() }
    }

    private fun hideCategorySidebar() {
        categorySidebar.visibility = View.GONE
    }

    private fun showTracksSidebar() {
        hideInfoBar()
        hideVodControls()
        player?.currentTracks?.let { refreshTrackOptions(it) }
        tracksSidebar.visibility = View.VISIBLE
        tracksSidebar.post {
            (audioTracksRecycler.getChildAt(0) ?: subtitleTracksRecycler.getChildAt(0))?.requestFocus()
        }
    }

    private fun hideTracksSidebar() {
        tracksSidebar.visibility = View.GONE
    }

    private fun hideAllOverlays() {
        hideChannelSidebar()
        hideCategorySidebar()
        hideTracksSidebar()
        hideInfoBar()
        hideVodControls()
    }

    // ---- VOD transport controls (movies/TV show episodes) ---------------------

    private fun showVodControls() {
        hideInfoBar()
        vodTitleText.text = currentChannel?.name ?: ""
        vodControls.visibility = View.VISIBLE
        updateVodProgressUi()
        vodProgressHandler.removeCallbacks(vodProgressRunnable)
        vodProgressHandler.post(vodProgressRunnable)
        hideVodControlsHandler.removeCallbacks(hideVodControlsRunnable)
        hideVodControlsHandler.postDelayed(hideVodControlsRunnable, INFO_BAR_AUTO_HIDE_MS)
    }

    private fun hideVodControls() {
        hideVodControlsHandler.removeCallbacks(hideVodControlsRunnable)
        vodProgressHandler.removeCallbacks(vodProgressRunnable)
        vodControls.visibility = View.GONE
    }

    private fun updateVodProgressUi() {
        val exoPlayer = player ?: return
        val duration = exoPlayer.duration
        val position = exoPlayer.currentPosition
        if (duration > 0) {
            vodProgressBar.progress = ((position.toDouble() / duration) * vodProgressBar.max).roundToInt()
                .coerceIn(0, vodProgressBar.max)
        }
        vodPositionText.text = formatDuration(position.coerceAtLeast(0))
        vodDurationText.text = if (duration > 0) formatDuration(duration) else "--:--"
        vodPlayPauseIcon.setImageResource(if (exoPlayer.isPlaying) R.drawable.ic_player_pause else R.drawable.ic_player_play)
    }

    private fun formatDuration(ms: Long): String {
        val totalSeconds = ms / 1000
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        return if (hours > 0) {
            String.format(Locale.getDefault(), "%d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format(Locale.getDefault(), "%02d:%02d", minutes, seconds)
        }
    }

    private fun togglePlayPause() {
        val exoPlayer = player ?: return
        if (exoPlayer.isPlaying) exoPlayer.pause() else exoPlayer.play()
        showVodControls()
    }

    private fun seekRelative(deltaMs: Long) {
        val exoPlayer = player ?: return
        val duration = exoPlayer.duration.takeIf { it > 0 } ?: Long.MAX_VALUE
        val target = (exoPlayer.currentPosition + deltaMs).coerceIn(0L, duration)
        exoPlayer.seekTo(target)
        showVodControls()
    }

    // ---- Key handling --------------------------------------------------------

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_LEFT -> {
                    // VOD: seek back instead of opening the live channel-switch sidebar, which a
                    // movie/episode has no use for (there's no "category" of other things playing)
                    // — unless the tracks sidebar is up, in which case LEFT/RIGHT simply has no VOD
                    // job to do (that sidebar is a single column with nothing to navigate to sideways).
                    if (isVod) {
                        if (tracksSidebar.visibility != View.VISIBLE) seekRelative(-10_000L)
                        return true
                    }
                    if (channelSidebar.visibility == View.VISIBLE) hideChannelSidebar() else showChannelSidebar()
                    return true
                }
                KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    if (isVod) {
                        if (tracksSidebar.visibility != View.VISIBLE) seekRelative(10_000L)
                        return true
                    }
                    if (categorySidebar.visibility == View.VISIBLE) hideCategorySidebar() else showCategorySidebar()
                    return true
                }
                KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_UP -> {
                    when {
                        channelSidebar.visibility == View.VISIBLE -> {
                            // Explicit position-based movement instead of trusting Android's
                            // default focus search — see moveChannelSidebarFocus for why (same
                            // underlying issue as MainActivity's channel list, same fix).
                            val focused = currentFocus
                            if (focused != null &&
                                moveChannelSidebarFocus(focused, down = event.keyCode == KeyEvent.KEYCODE_DPAD_DOWN)
                            ) {
                                return true
                            }
                        }
                        categorySidebar.visibility == View.VISIBLE || tracksSidebar.visibility == View.VISIBLE -> {
                            // Let default focus search move within that sidebar's own list.
                        }
                        event.keyCode == KeyEvent.KEYCODE_DPAD_DOWN -> {
                            if (isVod) showVodControls() else showInfoBar()
                            return true
                        }
                        event.keyCode == KeyEvent.KEYCODE_DPAD_UP -> {
                            // Audio/subtitle tracks — available for live channels too, many IPTV
                            // streams carry more than one audio/subtitle track.
                            showTracksSidebar()
                            return true
                        }
                    }
                }
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                    if (isVod &&
                        channelSidebar.visibility != View.VISIBLE &&
                        categorySidebar.visibility != View.VISIBLE &&
                        tracksSidebar.visibility != View.VISIBLE
                    ) {
                        togglePlayPause()
                        return true
                    }
                }
                KeyEvent.KEYCODE_MENU -> {
                    launchExternalPlayer()
                    return true
                }
                KeyEvent.KEYCODE_BACK -> {
                    if (channelSidebar.visibility == View.VISIBLE || categorySidebar.visibility == View.VISIBLE ||
                        tracksSidebar.visibility == View.VISIBLE
                    ) {
                        hideAllOverlays()
                        return true
                    }
                    if (infoBar.visibility == View.VISIBLE || vodControls.visibility == View.VISIBLE) {
                        hideInfoBar()
                        hideVodControls()
                        return true
                    }
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    /**
     * Explicitly moves focus to the next/previous channel row by adapter position rather than
     * relying on Android's automatic focus search — under fast D-pad key-repeat, RecyclerView
     * recycles the currently-focused row as it scrolls off-screen, and the default search can
     * fail mid-recycle and fall back to searching the whole window, landing focus somewhere
     * unrelated. See MainActivity.moveChannelFocus for the fuller explanation; same fix, same
     * underlying vulnerable pattern (a long D-pad-scrolled RecyclerView list).
     */
    private fun moveChannelSidebarFocus(focused: View, down: Boolean): Boolean {
        val position = channelSidebarRecycler.getChildAdapterPosition(focused)
        if (position == RecyclerView.NO_POSITION) return false
        val target = if (down) position + 1 else position - 1
        if (target !in 0 until channelAdapter.itemCount) return false

        val layoutManager = channelSidebarRecycler.layoutManager as? LinearLayoutManager ?: return false
        layoutManager.scrollToPosition(target)
        channelSidebarRecycler.post {
            layoutManager.findViewByPosition(target)?.requestFocus()
        }
        return true
    }

    private fun launchExternalPlayer() {
        val channel = currentChannel ?: return
        player?.pause()
        val uri = Uri.parse(channel.streamUrl)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "video/*")
            putExtra("title", channel.name)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            startActivity(Intent.createChooser(intent, getString(R.string.action_play_with)))
        } catch (e: Exception) {
            Toast.makeText(this, getString(R.string.error_no_external_player), Toast.LENGTH_LONG).show()
            player?.play()
        }
    }

    override fun onStop() {
        super.onStop()
        hideInfoBarHandler.removeCallbacks(hideInfoBarRunnable)
        hideVodControlsHandler.removeCallbacks(hideVodControlsRunnable)
        vodProgressHandler.removeCallbacks(vodProgressRunnable)
        clockHandler.removeCallbacks(clockRunnable)
        stallRecoveryRunnable?.let { stallRecoveryHandler.removeCallbacks(it) }
        // Actually ends playback — this is leaving fullscreen entirely. Returning to MainActivity
        // doesn't auto-resume a preview, so there's nothing to hand the player back to.
        PlayerHolder.removeListener(playerListener)
        PlayerHolder.errorListener = null
        PlayerHolder.release()
    }
}
