package com.ze.iptvplayer

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import androidx.lifecycle.lifecycleScope
import com.ze.iptvplayer.data.ChannelViewMode
import com.ze.iptvplayer.data.EpgSourceManager
import com.ze.iptvplayer.data.LongPressAction
import com.ze.iptvplayer.data.PlaylistRepository
import com.ze.iptvplayer.data.Prefs
import com.ze.iptvplayer.data.UiPrefs
import com.ze.iptvplayer.databinding.ActivitySettingsBinding
import com.ze.iptvplayer.databinding.ItemEpgSourceRowBinding
import com.ze.iptvplayer.databinding.ItemPlaylistRowBinding
import com.ze.iptvplayer.model.PlaylistSource
import com.ze.iptvplayer.model.SavedPlaylist
import kotlinx.coroutines.launch

class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.addPlaylistButton.setOnClickListener {
            startActivity(Intent(this, AddSourceActivity::class.java))
        }
        binding.addSourceButton.setOnClickListener { onAddSourceClicked() }
        binding.assignMappingButton.setOnClickListener {
            startActivity(Intent(this, ChannelMappingActivity::class.java))
        }
        binding.refreshEpgButton.setOnClickListener { onRefreshEpgClicked() }

        setupDisplayPrefs()
        setupLanguagePrefs()
        refreshPlaylistsList()
        refreshList()
    }

    override fun onResume() {
        super.onResume()
        refreshPlaylistsList()
        refreshList()
    }

    private fun playlistSourceSummary(source: PlaylistSource): String = when (source) {
        is PlaylistSource.M3uUrl -> getString(R.string.playlist_source_m3u_url, source.url)
        is PlaylistSource.M3uFile -> getString(
            R.string.playlist_source_m3u_file,
            android.net.Uri.parse(source.filePath).lastPathSegment ?: source.filePath
        )
        is PlaylistSource.Xtream -> getString(R.string.playlist_source_xtream, source.server, source.username)
    }

    private fun refreshPlaylistsList() {
        binding.playlistsList.removeAllViews()
        val playlists = Prefs.getAllPlaylists(this)

        if (playlists.isEmpty()) {
            val empty = TextView(this).apply {
                text = getString(R.string.label_no_playlists)
                setTextColor(0xFF888888.toInt())
            }
            binding.playlistsList.addView(empty)
            return
        }

        val activeId = Prefs.activePlaylistId(this)
        playlists.forEach { playlist ->
            val row = ItemPlaylistRowBinding.inflate(layoutInflater, binding.playlistsList, false)
            row.playlistNameText.text = playlist.name
            row.playlistSourceText.text = playlistSourceSummary(playlist.source)
            val isActive = playlist.id == activeId
            row.playlistActiveBadgeText.visibility = if (isActive) View.VISIBLE else View.GONE
            row.switchPlaylistButton.visibility = if (isActive) View.GONE else View.VISIBLE
            row.switchPlaylistButton.setOnClickListener { onSwitchPlaylistClicked(playlist) }
            row.editPlaylistButton.setOnClickListener {
                startActivity(
                    Intent(this, AddSourceActivity::class.java)
                        .putExtra(AddSourceActivity.EXTRA_PLAYLIST_ID, playlist.id)
                )
            }
            row.removePlaylistButton.setOnClickListener { onRemovePlaylistClicked(playlist) }
            binding.playlistsList.addView(row.root)
        }
    }

    /** Fetches [playlist]'s channels and, only once that succeeds, makes it the active one — a
     *  failed switch leaves the previously active playlist (and its already-loaded channels) alone. */
    private fun onSwitchPlaylistClicked(playlist: SavedPlaylist) {
        if (playlist.id == Prefs.activePlaylistId(this)) return
        binding.statusText.visibility = View.VISIBLE
        binding.statusText.text = getString(R.string.status_switching_playlist)
        lifecycleScope.launch {
            val result = PlaylistRepository.load(this@SettingsActivity, playlist.source)
            result.onSuccess {
                Prefs.setActivePlaylistId(this@SettingsActivity, playlist.id)
                binding.statusText.text = ""
                refreshPlaylistsList()
                refreshList() // the newly loaded playlist may bundle its own EPG source(s)
            }.onFailure { e ->
                Toast.makeText(this@SettingsActivity, getString(R.string.toast_failed_prefix, e.message), Toast.LENGTH_LONG).show()
                binding.statusText.text = ""
            }
        }
    }

    /** Removes a saved playlist. If it was active, switches to another saved playlist (if any
     *  remain) or sends the user back to the welcome screen if that was the last one. */
    private fun onRemovePlaylistClicked(playlist: SavedPlaylist) {
        val wasActive = playlist.id == Prefs.activePlaylistId(this)
        Prefs.removePlaylist(this, playlist.id)

        if (!wasActive) {
            refreshPlaylistsList()
            return
        }

        val next = Prefs.activePlaylist(this)
        if (next == null) {
            startActivity(Intent(this, StartActivity::class.java))
            finish()
            return
        }

        lifecycleScope.launch {
            val result = PlaylistRepository.load(this@SettingsActivity, next.source)
            result.onFailure { e ->
                Toast.makeText(this@SettingsActivity, getString(R.string.toast_failed_prefix, e.message), Toast.LENGTH_LONG).show()
            }
            refreshPlaylistsList()
            refreshList()
        }
    }

    private fun onRefreshEpgClicked() {
        binding.refreshEpgButton.isEnabled = false
        binding.statusText.visibility = View.VISIBLE
        binding.statusText.text = getString(R.string.status_refreshing_epg)
        lifecycleScope.launch {
            PlaylistRepository.reloadAllEpg(this@SettingsActivity)
            binding.statusText.text = getString(R.string.status_epg_refreshed)
            binding.refreshEpgButton.isEnabled = true
            refreshList()
        }
    }

    private fun setupDisplayPrefs() {
        if (UiPrefs.getDefaultViewMode(this) == ChannelViewMode.GRID) {
            binding.viewModeGrid.isChecked = true
        } else {
            binding.viewModeList.isChecked = true
        }
        binding.viewModeGroup.setOnCheckedChangeListener { _, checkedId ->
            val mode = if (checkedId == R.id.view_mode_grid) ChannelViewMode.GRID else ChannelViewMode.LIST
            UiPrefs.setDefaultViewMode(this, mode)
        }

        if (UiPrefs.getLongPressAction(this) == LongPressAction.QUICK_FAVORITE) {
            binding.longPressFavorite.isChecked = true
        } else {
            binding.longPressMenu.isChecked = true
        }
        binding.longPressGroup.setOnCheckedChangeListener { _, checkedId ->
            val action = if (checkedId == R.id.long_press_favorite) LongPressAction.QUICK_FAVORITE else LongPressAction.SHOW_MENU
            UiPrefs.setLongPressAction(this, action)
        }
    }

    /**
     * Uses AndroidX's per-app language API (AppCompatDelegate.setApplicationLocales), which
     * persists the choice automatically across restarts — no separate SharedPreferences needed
     * for this. Setting it recreates activities as needed to apply the new locale immediately.
     */
    private fun setupLanguagePrefs() {
        val current = AppCompatDelegate.getApplicationLocales().get(0)?.language
        if (current == "pt") {
            binding.languagePortuguese.isChecked = true
        } else {
            binding.languageEnglish.isChecked = true
        }
        binding.languageGroup.setOnCheckedChangeListener { _, checkedId ->
            val tag = if (checkedId == R.id.language_portuguese) "pt" else "en"
            AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(tag))
        }
    }

    private fun applyStatus(row: ItemEpgSourceRowBinding, sourceId: String) {
        val status = PlaylistRepository.epgSourceStatus(sourceId)
        when {
            status.errorMessage != null -> {
                row.sourceStatusText.text = getString(R.string.epg_status_error, status.errorMessage)
                row.sourceStatusText.setTextColor(0xFFE57373.toInt())
            }
            status.isLoaded -> {
                row.sourceStatusText.text = getString(R.string.epg_status_loaded, status.channelCount, status.programmeCount)
                row.sourceStatusText.setTextColor(0xFF6FA8DC.toInt())
            }
            else -> {
                row.sourceStatusText.text = getString(R.string.epg_status_not_loaded)
                row.sourceStatusText.setTextColor(0xFF888888.toInt())
            }
        }
    }

    /** Wires the Activate/Deactivate button and active badge/note for one source row — shared by
     *  both primary and extra rows, since either kind can be the forced active source. */
    private fun applyActiveToggle(row: ItemEpgSourceRowBinding, sourceId: String) {
        val isActive = PlaylistRepository.activeEpgSourceOverride(this) == sourceId
        row.activeBadgeText.visibility = if (isActive) View.VISIBLE else View.GONE
        row.activeSourceNoteText.visibility = if (isActive) View.VISIBLE else View.GONE
        row.toggleActiveButton.text = getString(if (isActive) R.string.action_deactivate else R.string.action_activate)
        row.toggleActiveButton.setOnClickListener {
            val current = PlaylistRepository.activeEpgSourceOverride(this)
            PlaylistRepository.setActiveEpgSourceOverride(this, if (current == sourceId) null else sourceId)
            refreshList()
        }
    }

    private fun refreshList(focusSourceId: String? = null) {
        binding.sourcesList.removeAllViews()

        // Primary (provider-bundled) source(s) — not removable, but still eligible to be the
        // forced "active" source. An M3U can declare more than one XMLTV feed at once, so this is
        // a loop, not a single fixed row.
        val allSources = PlaylistRepository.availableEpgSources(this)
        allSources.filter { EpgSourceManager.isPrimarySourceId(it.first) }.forEach { (id, label) ->
            val row = ItemEpgSourceRowBinding.inflate(layoutInflater, binding.sourcesList, false)
            row.sourceNameText.text = label
            row.sourceUrlText.text = getString(R.string.label_loaded_automatically)
            applyStatus(row, id)
            applyActiveToggle(row, id)
            row.removeSourceButton.visibility = View.GONE
            binding.sourcesList.addView(row.root)
        }

        EpgSourceManager.getAll(this).forEach { source ->
            val row = ItemEpgSourceRowBinding.inflate(layoutInflater, binding.sourcesList, false)
            row.sourceNameText.text = source.name
            row.sourceUrlText.text = source.url
            applyStatus(row, source.id)
            applyActiveToggle(row, source.id)
            row.removeSourceButton.setOnClickListener {
                EpgSourceManager.remove(this, source.id)
                refreshList()
            }
            binding.sourcesList.addView(row.root)
            // Right after adding a source, land focus directly on its Remove button — the
            // Settings screen is one long scrolling form, and D-pad RIGHT from the "Add source"
            // area has no reliable path to a dynamically-added row several sections away.
            if (source.id == focusSourceId) {
                row.removeSourceButton.requestFocus()
            }
        }

        if (allSources.isEmpty()) {
            val empty = TextView(this).apply {
                text = getString(R.string.label_no_epg_sources)
                setTextColor(0xFF888888.toInt())
            }
            binding.sourcesList.addView(empty)
        }
    }

    private fun onAddSourceClicked() {
        val name = binding.newSourceNameEdit.text.toString().trim()
        val url = binding.newSourceUrlEdit.text.toString().trim()
        if (name.isEmpty() || url.isEmpty()) {
            Toast.makeText(this, getString(R.string.toast_enter_name_and_url), Toast.LENGTH_SHORT).show()
            return
        }

        val source = EpgSourceManager.add(this, name, url)
        binding.newSourceNameEdit.text.clear()
        binding.newSourceUrlEdit.text.clear()
        refreshList(focusSourceId = source.id)

        binding.statusText.visibility = View.VISIBLE
        binding.statusText.text = getString(R.string.status_loading_named, name)
        lifecycleScope.launch {
            PlaylistRepository.reloadEpgSource(source.id, source.url)
            binding.statusText.text = getString(R.string.status_loaded_named, name)
            refreshList(focusSourceId = source.id)
        }
    }
}
