package com.ze.iptvplayer

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.PopupWindow
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.ze.iptvplayer.data.ChannelViewMode
import com.ze.iptvplayer.data.FavoritesManager
import com.ze.iptvplayer.data.LongPressAction
import com.ze.iptvplayer.data.PlaylistRepository
import com.ze.iptvplayer.data.Prefs
import com.ze.iptvplayer.data.UiPrefs
import com.ze.iptvplayer.databinding.ActivityMainBinding
import com.ze.iptvplayer.databinding.PopupChannelActionsBinding
import com.ze.iptvplayer.model.Channel
import com.ze.iptvplayer.model.ContentType
import com.ze.iptvplayer.player.PlayerHolder
import com.ze.iptvplayer.ui.CategoryEntry
import com.ze.iptvplayer.ui.ChannelListAdapter
import com.ze.iptvplayer.ui.SidebarCategoryAdapter
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private companion object {
        const val GRID_SPAN_COUNT = 5
        const val SIDEBAR_PANEL_WIDTH_DP = 216
        const val SIDEBAR_ANIM_MS = 200L
    }

    private lateinit var binding: ActivityMainBinding
    private lateinit var categoryAdapter: SidebarCategoryAdapter
    private lateinit var channelAdapter: ChannelListAdapter

    private var currentCategoryId: String = PlaylistRepository.CATEGORY_ALL
    /** Which sidebar section (Live / Movies / TV Shows) is currently browsed. The two extra
     *  sections only appear at all when the active playlist actually has entries classified that
     *  way (see [updateSectionIconsVisibility]) — most providers only ever have LIVE. */
    private var currentSection: ContentType = ContentType.LIVE
    private var isGridMode = false
    private var sidebarExpanded = true
    private var uiReady = false

    /** Id of whichever [com.ze.iptvplayer.model.SavedPlaylist] is currently reflected in
     *  [PlaylistRepository] (a process-wide singleton). Compared against Prefs.activePlaylistId()
     *  in onResume() to notice a switch/edit/removal made in SettingsActivity while this screen
     *  was in the background — Settings already performed the actual reload, so this activity only
     *  needs to know it happened and rebuild its own views to match. */
    private var loadedPlaylistId: String? = null

    private var previewingChannelId: String? = null
    // Set right before starting PlayerActivity so onStop() (which fires immediately after,
    // since starting another Activity stops this one) knows NOT to release the shared player —
    // PlayerActivity is taking over that exact same instance, not starting a new one.
    private var pendingFullscreenHandoff = false

    // Ticks once a second while the screen is visible; stopped in onPause to avoid updating a
    // clock nobody can see while another screen is in front.
    private val clockHandler = Handler(Looper.getMainLooper())
    private val clockRunnable = object : Runnable {
        override fun run() {
            binding.mainClockText.text = SimpleDateFormat("HH:mm", Locale.getDefault()).format(System.currentTimeMillis())
            clockHandler.postDelayed(this, 1000L)
        }
    }

    // Explicit zone-navigation state — deliberately NOT relying on Android's automatic
    // geometric focus search between the icon rail / category panel / channel content,
    // since that produced inconsistent jumps (e.g. skipping the category panel entirely).
    private var lastFocusedRailIcon: View? = null
    private var lastFocusedCategoryPosition: Int = 0
    private var lastFocusedChannelPosition: Int = 0
    private var wasLastFocusInChannelContent: Boolean = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Same reasoning as PlayerActivity — a channel preview running here shouldn't let the
        // system's idle timeout kick in, and keeping the screen awake while actively browsing
        // is normal expected behavior for a TV app anyway.
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val playlist = Prefs.activePlaylist(this)
        if (playlist == null) {
            // Shouldn't normally happen — StartActivity gates access here — but redirect to the
            // front door rather than AddSourceActivity directly, for consistency if it ever does.
            startActivity(Intent(this, StartActivity::class.java))
            finish()
            return
        }

        if (PlaylistRepository.channels.isEmpty()) {
            lifecycleScope.launch {
                // Channel list first, guide data after: waiting for every EPG source to download
                // and parse before drawing anything left the screen blank for a long time on a
                // cold start (e.g. when the app is restarted after being killed in the background).
                val result = PlaylistRepository.load(this@MainActivity, playlist.source, loadEpg = false)
                result.onSuccess {
                    loadedPlaylistId = playlist.id
                    setupUi()
                    loadEpgInBackground()
                }.onFailure { e ->
                    Toast.makeText(this@MainActivity, getString(R.string.toast_failed_load_playlist, e.message), Toast.LENGTH_LONG).show()
                    startActivity(Intent(this@MainActivity, AddSourceActivity::class.java))
                    finish()
                }
            }
        } else {
            loadedPlaylistId = playlist.id
            setupUi()
        }
    }

    override fun onResume() {
        super.onResume()
        if (uiReady) {
            if (Prefs.activePlaylistId(this) != loadedPlaylistId) {
                // Switched, edited, or the active one got removed in SettingsActivity while this
                // screen was in the background — reload to match rather than just re-rendering.
                reloadActivePlaylist()
            } else {
                // Picks up favorite/mapping changes made in other screens since this was last shown.
                refreshCurrentCategory()
            }
        }
        clockHandler.post(clockRunnable)
    }

    /** (Re)loads whichever playlist is now active and rebuilds the channel/category lists to match
     *  it — called from onResume() when Settings changed which playlist is active out from under
     *  this already-running screen. */
    private fun reloadActivePlaylist() {
        val playlist = Prefs.activePlaylist(this)
        if (playlist == null) {
            startActivity(Intent(this, StartActivity::class.java))
            finish()
            return
        }
        lifecycleScope.launch {
            val result = PlaylistRepository.load(this@MainActivity, playlist.source, loadEpg = false)
            result.onSuccess {
                loadedPlaylistId = playlist.id
                updateSectionIconsVisibility()
                if (!PlaylistRepository.hasContentType(currentSection)) currentSection = ContentType.LIVE
                val targetCategory = if (currentSection == ContentType.LIVE) {
                    playlist.lastCategoryId ?: PlaylistRepository.CATEGORY_ALL
                } else {
                    PlaylistRepository.CATEGORY_ALL
                }
                categoryAdapter.updateCategories(buildCategoryEntries(), targetCategory)
                selectCategory(targetCategory)
                loadEpgInBackground()
            }.onFailure { e ->
                Toast.makeText(this@MainActivity, getString(R.string.toast_failed_load_playlist, e.message), Toast.LENGTH_LONG).show()
            }
        }
    }

    /** Downloads and parses every EPG source while the channel list is already on screen, then
     *  re-binds the visible rows in place so their now/next lines fill in without moving the
     *  user's scroll position or focus. */
    private fun loadEpgInBackground() {
        lifecycleScope.launch {
            PlaylistRepository.reloadAllEpg(this@MainActivity)
            if (uiReady) channelAdapter.rebindAll()
        }
    }

    override fun onPause() {
        super.onPause()
        clockHandler.removeCallbacks(clockRunnable)
    }

    override fun onStop() {
        super.onStop()
        if (pendingFullscreenHandoff) {
            // PlayerActivity is taking over the shared player instance we just detached from
            // the preview — don't release it out from under it.
            pendingFullscreenHandoff = false
        } else {
            // Genuinely leaving without going fullscreen — don't leave a preview stream
            // decoding in the background while another screen is open.
            releasePreview()
        }
    }

    private fun setupUi() {
        isGridMode = UiPrefs.getDefaultViewMode(this) == ChannelViewMode.GRID
        updateViewToggleLabel()

        channelAdapter = ChannelListAdapter(
            context = this,
            channels = emptyList(),
            isGrid = isGridMode,
            onClick = { channel -> onChannelSelected(channel) },
            onLongClick = { channel, anchor -> onChannelLongPress(channel, anchor) }
        )
        binding.channelRecycler.adapter = channelAdapter
        // Default view cache (2) means rows scrolled just off-screen get destroyed almost
        // immediately, so scrolling back into them needs a full rebind. Combined with fast
        // key-repeat on D-pad DOWN, focus-search can outrun the binding of brand-new rows
        // entering the viewport for the first time, occasionally landing focus somewhere
        // unexpected instead of the immediately-next row. A larger cache keeps more rows
        // pre-built and ready regardless of scroll direction.
        binding.channelRecycler.setItemViewCacheSize(20)
        // DiffUtil-driven list swaps (switching category) default to fading changed/added/
        // removed rows in and out — a nice touch for small changes, but adds needless visible
        // delay when the whole list swaps at once, which is the only time this fires (rows
        // simply scrolling on/off-screen never triggers item animations either way).
        binding.channelRecycler.itemAnimator = null
        applyChannelLayoutManager()

        categoryAdapter = SidebarCategoryAdapter(buildCategoryEntries(), currentCategoryId) { entry ->
            selectCategory(entry.id)
        }
        binding.categoryRecycler.layoutManager = LinearLayoutManager(this)
        binding.categoryRecycler.adapter = categoryAdapter

        selectCategory(PlaylistRepository.CATEGORY_ALL)

        binding.iconSearch.setOnClickListener {
            startActivity(Intent(this, SearchActivity::class.java))
        }
        binding.iconSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        binding.iconLive.setOnClickListener { selectSection(ContentType.LIVE) }
        binding.iconMovies.setOnClickListener { selectSection(ContentType.MOVIE) }
        binding.iconSeries.setOnClickListener { selectSection(ContentType.SERIES) }
        binding.iconFavorites.setOnClickListener {
            if (FavoritesManager.getAll(this).isEmpty()) {
                Toast.makeText(this, getString(R.string.toast_no_favorites_yet), Toast.LENGTH_SHORT).show()
            } else {
                currentSection = ContentType.LIVE
                categoryAdapter.updateCategories(buildCategoryEntries(), PlaylistRepository.CATEGORY_FAVORITES)
                selectCategory(PlaylistRepository.CATEGORY_FAVORITES)
                binding.categoryRecycler.requestFocus()
            }
        }
        binding.iconTvGuide.setOnClickListener {
            startActivity(Intent(this, EpgGridActivity::class.java))
        }
        binding.viewToggleButton.setOnClickListener {
            isGridMode = !isGridMode
            channelAdapter.setGridMode(isGridMode)
            applyChannelLayoutManager()
            updateViewToggleLabel()
        }

        // Keep the visual expand/collapse truthful regardless of how focus got somewhere
        // (click, our own key handling below, etc). Also remember the last-focused channel
        // row live, so a list refresh (returning from the player, toggling a favorite) can
        // restore focus there instead of Android's default fallback landing on the icon rail.
        binding.root.viewTreeObserver.addOnGlobalFocusChangeListener { _, newFocus ->
            if (newFocus == null) return@addOnGlobalFocusChangeListener
            val inSidebar = isDescendantOf(newFocus, binding.sidebarRail) || isDescendantOf(newFocus, binding.categoryPanel)
            animateSidebar(inSidebar)
            wasLastFocusInChannelContent = isDescendantOf(newFocus, binding.channelRecycler)
            if (wasLastFocusInChannelContent) {
                lastFocusedChannelPosition = binding.channelRecycler.getChildAdapterPosition(newFocus)
            }
        }

        trackRailFocus()
        updateSectionIconsVisibility()

        binding.categoryRecycler.post { binding.categoryRecycler.getChildAt(0)?.requestFocus() }

        prefetchChannelLogos()
        uiReady = true
    }

    /** Shows the Movies/TV Shows rail icons only when the active playlist actually has entries
     *  classified that way (see ContentTypeClassifier) — most providers only ever ship live
     *  channels, and an always-visible empty tab would just be clutter for them. */
    private fun updateSectionIconsVisibility() {
        binding.iconMovies.visibility = if (PlaylistRepository.hasContentType(ContentType.MOVIE)) View.VISIBLE else View.GONE
        binding.iconSeries.visibility = if (PlaylistRepository.hasContentType(ContentType.SERIES)) View.VISIBLE else View.GONE
    }

    /** Switches which section (Live / Movies / TV Shows) the category panel and channel list are
     *  browsing. A no-op if already on that section, other than returning focus to the category
     *  panel — pressing the same rail icon again shouldn't reset your place in that section. */
    private fun selectSection(section: ContentType) {
        if (section == currentSection) {
            binding.categoryRecycler.requestFocus()
            return
        }
        currentSection = section
        categoryAdapter.updateCategories(buildCategoryEntries(), PlaylistRepository.CATEGORY_ALL)
        selectCategory(PlaylistRepository.CATEGORY_ALL)
        binding.categoryRecycler.requestFocus()
    }

    /**
     * Warms Glide's cache for every channel logo right after the playlist loads, so scrolling
     * later hits an already-cached image instead of kicking off a fresh network fetch the moment
     * a row scrolls into view — the thing that causes logo pop-in during fast scrolling. Glide's
     * own request queue and disk cache handle the actual work in the background; this just tells
     * it what to fetch ahead of time.
     */
    private fun prefetchChannelLogos() {
        PlaylistRepository.channels.asSequence()
            .mapNotNull { it.logoUrl }
            .filter { it.isNotBlank() }
            .distinct()
            .forEach { url -> Glide.with(this).load(url).preload() }
    }

    /**
     * Intercepts LEFT/RIGHT before any view gets to handle them. This has to happen here,
     * not via setOnKeyListener on the rail/RecyclerView containers: a focused leaf view's own
     * default onKeyDown() runs Android's automatic geometric focus search and consumes the event
     * immediately if it finds *any* candidate, before the event would ever bubble up to a
     * container's listener. That's what caused focus to jump straight from a rail icon into the
     * channel list — the automatic search found something over there and stopped looking.
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (uiReady && event.action == KeyEvent.ACTION_DOWN &&
            (event.keyCode == KeyEvent.KEYCODE_DPAD_LEFT || event.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT ||
                event.keyCode == KeyEvent.KEYCODE_DPAD_DOWN || event.keyCode == KeyEvent.KEYCODE_DPAD_UP)
        ) {
            val focused = currentFocus
            if (focused != null && handleZoneKey(event.keyCode, focused)) {
                return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    /** Returns true if the key was consumed by our explicit zone-transition logic. */
    private fun handleZoneKey(keyCode: Int, focused: View): Boolean {
        val inRail = isDescendantOf(focused, binding.sidebarRail)
        val inCategoryPanel = isDescendantOf(focused, binding.categoryPanel)
        val inChannelContent = isDescendantOf(focused, binding.channelRecycler)

        return when (keyCode) {
            KeyEvent.KEYCODE_DPAD_RIGHT -> when {
                inRail -> { focusCategoryPanel(); true }
                inCategoryPanel -> { focusChannelContent(); true }
                else -> false
            }
            KeyEvent.KEYCODE_DPAD_LEFT -> when {
                inCategoryPanel -> {
                    binding.categoryRecycler.focusedChild?.let {
                        lastFocusedCategoryPosition = binding.categoryRecycler.getChildAdapterPosition(it)
                    }
                    (lastFocusedRailIcon ?: binding.iconLive).requestFocus()
                    true
                }
                inChannelContent -> {
                    val position = binding.channelRecycler.getChildAdapterPosition(focused)
                    val atLeftEdge = if (isGridMode) position % GRID_SPAN_COUNT == 0 else true
                    if (atLeftEdge) { focusCategoryPanel(); true } else false
                }
                else -> false
            }
            KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_UP -> when {
                inChannelContent -> moveChannelFocus(focused, down = keyCode == KeyEvent.KEYCODE_DPAD_DOWN)
                else -> false
            }
            else -> false
        }
    }

    /**
     * Explicitly moves focus to the next/previous channel row by adapter position, rather than
     * relying on Android's automatic geometric focus search. Under fast D-pad key-repeat (holding
     * the button down), RecyclerView recycles the currently-focused row's view for reuse as it
     * scrolls off-screen; when that happens mid-search, Android's focus search can fail to find a
     * sensible "next" candidate and falls back to searching the *entire window*, occasionally
     * landing focus on something totally unrelated (a rail icon, observed in testing) instead of
     * the next channel down. Scrolling to the target position first, then focusing it once it's
     * actually laid out, sidesteps the automatic search (and its failure mode) entirely.
     */
    private fun moveChannelFocus(focused: View, down: Boolean): Boolean {
        val recycler = binding.channelRecycler
        val position = recycler.getChildAdapterPosition(focused)
        if (position == RecyclerView.NO_POSITION) return false

        val step = if (isGridMode) GRID_SPAN_COUNT else 1
        val target = if (down) position + step else position - step
        if (target !in 0 until channelAdapter.itemCount) return false

        val layoutManager = recycler.layoutManager as? LinearLayoutManager ?: return false
        layoutManager.scrollToPosition(target)
        recycler.post {
            layoutManager.findViewByPosition(target)?.requestFocus()
        }
        return true
    }

    private fun trackRailFocus() {
        val labels = mapOf(
            binding.iconSearch to getString(R.string.nav_search),
            binding.iconLive to getString(R.string.nav_live_tv),
            binding.iconMovies to getString(R.string.nav_movies),
            binding.iconSeries to getString(R.string.nav_series),
            binding.iconFavorites to getString(R.string.nav_favorites),
            binding.iconTvGuide to getString(R.string.nav_tv_guide),
            binding.iconSettings to getString(R.string.nav_settings)
        )
        labels.forEach { (icon, label) ->
            icon.setOnFocusChangeListener { v, hasFocus ->
                if (hasFocus) {
                    lastFocusedRailIcon = v
                    showRailTooltip(v, label)
                } else {
                    hideRailTooltip()
                }
            }
        }
    }

    /** Floating label beside whichever rail icon currently has focus — the rail itself stays icon-only. */
    private fun showRailTooltip(icon: View, label: String) {
        binding.railTooltip.text = label
        binding.railTooltip.visibility = View.VISIBLE
        binding.railTooltip.post {
            // Posted so the tooltip has already been measured (needed for its .height below) —
            // otherwise the very first time it's shown, height would still read as 0.
            val params = binding.railTooltip.layoutParams as ViewGroup.MarginLayoutParams
            params.topMargin = icon.top + (icon.height - binding.railTooltip.height) / 2
            binding.railTooltip.layoutParams = params
        }
    }

    private fun hideRailTooltip() {
        binding.railTooltip.visibility = View.GONE
    }

    private fun focusCategoryPanel() {
        if (!sidebarExpanded) animateSidebar(true)
        binding.categoryPanel.post {
            val layoutManager = binding.categoryRecycler.layoutManager as? LinearLayoutManager
            val target = layoutManager?.findViewByPosition(lastFocusedCategoryPosition)
            (target ?: binding.categoryRecycler.getChildAt(0))?.requestFocus()
        }
    }

    private fun focusChannelContent() {
        binding.channelRecycler.post {
            if (binding.channelRecycler.getChildAt(0)?.requestFocus() != true) {
                binding.channelRecycler.requestFocus()
            }
        }
    }

    // ---- Categories -------------------------------------------------------

    private fun buildCategoryEntries(): List<CategoryEntry> =
        PlaylistRepository.categories(this, currentSection).map { (id, label) -> CategoryEntry(id, label) }

    private fun selectCategory(id: String) {
        releasePreview()
        currentCategoryId = id
        channelAdapter.submitList(PlaylistRepository.channelsForCategory(this, id, currentSection))
        binding.contentTitle.text = when {
            id == PlaylistRepository.CATEGORY_FAVORITES -> getString(R.string.category_favorites)
            id == PlaylistRepository.CATEGORY_ALL && currentSection == ContentType.MOVIE -> getString(R.string.category_all_movies)
            id == PlaylistRepository.CATEGORY_ALL && currentSection == ContentType.SERIES -> getString(R.string.category_all_series)
            id == PlaylistRepository.CATEGORY_ALL -> getString(R.string.category_all_channels)
            id == Channel.UNGROUPED_GROUP_KEY -> getString(R.string.category_uncategorized)
            else -> id
        }
        // Only Live's last-viewed category is remembered per playlist — Movies/TV Shows always
        // reopen on "All" for now, same as switching sections within one session.
        if (currentSection == ContentType.LIVE) {
            loadedPlaylistId?.let { Prefs.setLastCategoryForPlaylist(this, it, id) }
        }
    }

    /** Re-derives categories + the current list after something external changed (favorites, new EPG mapping, etc). */
    private fun refreshCurrentCategory() {
        val finalId = if (currentCategoryId == PlaylistRepository.CATEGORY_FAVORITES &&
            PlaylistRepository.channelsForCategory(this, PlaylistRepository.CATEGORY_FAVORITES, currentSection).isEmpty()
        ) {
            PlaylistRepository.CATEGORY_ALL
        } else {
            currentCategoryId
        }
        categoryAdapter.updateCategories(buildCategoryEntries(), finalId)
        selectCategory(finalId)
        if (wasLastFocusInChannelContent) restoreChannelFocus()
    }

    /**
     * Rebuilding the channel list (submitList -> notifyDataSetChanged) invalidates whatever row
     * was focused, and Android's fallback focus search lands on the first focusable view in the
     * window — the icon rail — instead of staying in the channel list. This puts focus back where
     * the user almost certainly still wants it, since every caller of refreshCurrentCategory()
     * (returning from the player, toggling a favorite) originates from an action taken there.
     */
    private fun restoreChannelFocus() {
        binding.channelRecycler.post {
            val count = channelAdapter.itemCount
            if (count == 0) return@post
            val safePosition = lastFocusedChannelPosition.coerceIn(0, count - 1)
            val layoutManager = binding.channelRecycler.layoutManager
            val target = (layoutManager as? LinearLayoutManager)?.findViewByPosition(safePosition)
                ?: (layoutManager as? GridLayoutManager)?.findViewByPosition(safePosition)
            (target ?: binding.channelRecycler.getChildAt(0))?.requestFocus()
        }
    }

    // ---- Channel view mode --------------------------------------------------

    private fun applyChannelLayoutManager() {
        binding.channelRecycler.layoutManager = if (isGridMode) {
            GridLayoutManager(this, GRID_SPAN_COUNT).apply { initialPrefetchItemCount = GRID_SPAN_COUNT * 4 }
        } else {
            LinearLayoutManager(this).apply { initialPrefetchItemCount = 8 }
        }
    }

    private fun updateViewToggleLabel() {
        binding.viewToggleButton.text = if (isGridMode) getString(R.string.list_view_label) else getString(R.string.grid_view_label)
    }

    private fun openPlayer(channel: Channel) {
        val intent = Intent(this, PlayerActivity::class.java)
        intent.putExtra(PlayerActivity.EXTRA_CHANNEL_ID, channel.key)
        intent.putExtra(PlayerActivity.EXTRA_CATEGORY_ID, currentCategoryId)
        startActivity(intent)
    }

    // ---- Inline preview (first OK = small preview, second OK on the same channel = fullscreen) ----

    private fun onChannelSelected(channel: Channel) {
        if (channel.key == previewingChannelId) {
            pendingFullscreenHandoff = true
            detachPreviewForFullscreen()
            openPlayer(channel)
        } else {
            startPreview(channel)
        }
    }

    private fun startPreview(channel: Channel) {
        // Stops any DIFFERENT channel's preview first — a real switch, not a handoff, so there's
        // no existing buffer worth keeping.
        releasePreview()

        PlayerHolder.playChannel(this, channel)
        PlayerHolder.attach(this, binding.previewPlayerView)

        previewingChannelId = channel.key
        binding.previewChannelName.text = channel.name
        updatePreviewProgrammeInfo(channel)
        binding.previewSection.visibility = View.VISIBLE
    }

    private fun updatePreviewProgrammeInfo(channel: Channel) {
        val fmt = SimpleDateFormat("HH:mm", Locale.getDefault())
        val (current, next) = PlaylistRepository.nowNext(this, channel)
        if (current != null) {
            binding.previewNowProgramme.text = "${fmt.format(current.startMillis)}–${fmt.format(current.stopMillis)}  ${current.title}"
            binding.previewNowProgramme.visibility = View.VISIBLE
        } else {
            binding.previewNowProgramme.visibility = View.GONE
        }
        if (next != null) {
            binding.previewNextProgramme.text = "${fmt.format(next.startMillis)}  ${next.title}"
            binding.previewNextProgramme.visibility = View.VISIBLE
        } else {
            binding.previewNextProgramme.visibility = View.GONE
        }
        if (current == null && next == null) {
            binding.previewNowProgramme.text = getString(R.string.label_no_programme_info)
            binding.previewNowProgramme.visibility = View.VISIBLE
        }
    }

    /** Actually stops playback — use when the preview should genuinely end, not hand off. */
    private fun releasePreview() {
        PlayerHolder.release()
        previewingChannelId = null
        binding.previewPlayerView.player = null
        binding.previewSection.visibility = View.GONE
    }

    /** Unbinds the preview surface WITHOUT stopping playback — PlayerActivity is about to attach
     *  the same still-running shared player to its own fullscreen view. */
    private fun detachPreviewForFullscreen() {
        PlayerHolder.detach(binding.previewPlayerView)
        previewingChannelId = null
        binding.previewSection.visibility = View.GONE
    }

    // ---- Long-press actions -------------------------------------------------

    private fun onChannelLongPress(channel: Channel, anchor: View) {
        if (UiPrefs.getLongPressAction(this) == LongPressAction.QUICK_FAVORITE) {
            val nowFavorite = FavoritesManager.toggle(this, channel.key)
            Toast.makeText(
                this,
                if (nowFavorite) getString(R.string.toast_added_to_favorites) else getString(R.string.toast_removed_from_favorites),
                Toast.LENGTH_SHORT
            ).show()
            refreshCurrentCategory()
            return
        }
        showChannelActionsPopup(channel, anchor)
    }

    private fun showChannelActionsPopup(channel: Channel, anchor: View) {
        val popupBinding = PopupChannelActionsBinding.inflate(layoutInflater)
        val isFavorite = FavoritesManager.isFavorite(this, channel.key)
        popupBinding.actionFavorite.text = if (isFavorite) getString(R.string.popup_action_remove_favorite) else getString(R.string.popup_action_add_favorite)

        val popupWindow = PopupWindow(
            popupBinding.root,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            true
        )
        popupWindow.isOutsideTouchable = true

        popupBinding.actionFavorite.setOnClickListener {
            FavoritesManager.toggle(this, channel.key)
            popupWindow.dismiss()
            refreshCurrentCategory()
        }
        popupBinding.actionAssignEpg.setOnClickListener {
            popupWindow.dismiss()
            val intent = Intent(this, ChannelMappingActivity::class.java)
            intent.putExtra(ChannelMappingActivity.EXTRA_PRESELECT_CHANNEL_ID, channel.key)
            startActivity(intent)
        }

        popupWindow.showAsDropDown(anchor)
        popupBinding.actionFavorite.requestFocus()
    }

    // ---- Sidebar collapse/expand ---------------------------------------------

    private fun isDescendantOf(view: View, ancestor: View): Boolean {
        var v: View? = view
        while (v != null) {
            if (v === ancestor) return true
            v = v.parent as? View
        }
        return false
    }

    private fun animateSidebar(expand: Boolean) {
        if (expand == sidebarExpanded) return
        sidebarExpanded = expand

        val targetWidthPx = if (expand) dpToPx(SIDEBAR_PANEL_WIDTH_DP) else 0
        if (expand) binding.categoryPanel.visibility = View.VISIBLE

        val currentWidth = binding.categoryPanel.width
        val startWidth = if (currentWidth > 0 || !expand) currentWidth else 0

        ValueAnimator.ofInt(startWidth, targetWidthPx).apply {
            duration = SIDEBAR_ANIM_MS
            addUpdateListener { anim ->
                val params = binding.categoryPanel.layoutParams
                params.width = anim.animatedValue as Int
                binding.categoryPanel.layoutParams = params
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    if (!expand) binding.categoryPanel.visibility = View.GONE
                }
            })
            start()
        }
    }

    private fun dpToPx(dp: Int): Int = (dp * resources.displayMetrics.density).toInt()
}
