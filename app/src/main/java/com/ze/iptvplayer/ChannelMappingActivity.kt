package com.ze.iptvplayer

import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import android.widget.AdapterView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.ze.iptvplayer.data.EpgMappingStore
import com.ze.iptvplayer.data.EpgSourceManager
import com.ze.iptvplayer.data.PlaylistRepository
import com.ze.iptvplayer.databinding.ActivityChannelMappingBinding
import com.ze.iptvplayer.databinding.ItemMappingRowBinding
import com.ze.iptvplayer.model.Channel
import com.ze.iptvplayer.model.EpgChannelMapping
import kotlinx.coroutines.launch

class ChannelMappingActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_PRESELECT_CHANNEL_ID = "extra_preselect_channel_id"
    }

    private lateinit var binding: ActivityChannelMappingBinding

    private var channels: List<Channel> = emptyList()
    private var sources: List<Pair<String, String>> = emptyList() // (id, name)
    private var guideChannelIds: List<String> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityChannelMappingBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // EPG mapping only makes sense for live channels — movies/TV show episodes never have a
        // guide entry to assign.
        channels = PlaylistRepository.channels
            .filter { it.contentType == com.ze.iptvplayer.model.ContentType.LIVE }
            .sortedBy { it.name.lowercase() }
        binding.channelSpinner.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item, channels.map { it.name }
        )

        val preselectId = intent.getStringExtra(EXTRA_PRESELECT_CHANNEL_ID)
        if (preselectId != null) {
            val index = channels.indexOfFirst { it.key == preselectId }
            if (index >= 0) binding.channelSpinner.setSelection(index)
        }

        sources = PlaylistRepository.availableEpgSources(this)
        binding.sourceSpinner.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item, sources.map { it.second }
        )

        binding.sourceSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                populateGuideChannelSpinner(position)
            }
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
        if (sources.isNotEmpty()) populateGuideChannelSpinner(0)

        binding.saveMappingButton.setOnClickListener { onSaveClicked() }

        refreshMappingsList()
    }

    private fun populateGuideChannelSpinner(sourcePosition: Int) {
        if (sourcePosition !in sources.indices) return
        val (sourceId, _) = sources[sourcePosition]
        val names = PlaylistRepository.channelNamesForSource(sourceId)

        if (names.isEmpty() && !EpgSourceManager.isPrimarySourceId(sourceId)) {
            // Extra source's guide list hasn't been fetched yet in this session — fetch it now.
            val source = EpgSourceManager.getAll(this).firstOrNull { it.id == sourceId }
            if (source != null) {
                lifecycleScope.launch {
                    PlaylistRepository.reloadEpgSource(source.id, source.url)
                    // Only refresh if the user hasn't since switched to a different source.
                    if (binding.sourceSpinner.selectedItemPosition == sourcePosition) {
                        populateGuideChannelSpinner(sourcePosition)
                    }
                }
                return
            }
        }

        guideChannelIds = names.keys.sortedBy { names[it]?.lowercase() ?: it }
        val labels = guideChannelIds.map { id -> "${names[id] ?: id} ($id)" }
        binding.guideChannelSpinner.adapter = if (labels.isEmpty()) {
            ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, listOf(getString(R.string.label_no_guide_channels_loaded)))
        } else {
            ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, labels)
        }
    }

    private fun onSaveClicked() {
        val channelPos = binding.channelSpinner.selectedItemPosition
        val sourcePos = binding.sourceSpinner.selectedItemPosition
        val guidePos = binding.guideChannelSpinner.selectedItemPosition

        if (channelPos !in channels.indices || sourcePos !in sources.indices || guidePos !in guideChannelIds.indices) {
            Toast.makeText(this, getString(R.string.toast_pick_channel_source_guide), Toast.LENGTH_SHORT).show()
            return
        }

        val channel = channels[channelPos]
        val (sourceId, _) = sources[sourcePos]
        val guideChannelId = guideChannelIds[guidePos]

        EpgMappingStore.setMapping(this, EpgChannelMapping(channel.key, sourceId, guideChannelId))
        binding.statusText.visibility = View.VISIBLE
        binding.statusText.text = getString(R.string.status_saved_mapping, channel.name, guideChannelId)
        refreshMappingsList()
    }

    private fun refreshMappingsList() {
        binding.mappingsList.removeAllViews()
        val mappings = EpgMappingStore.getAll(this)

        if (mappings.isEmpty()) {
            binding.mappingsList.addView(
                android.widget.TextView(this).apply {
                    text = getString(R.string.label_no_mappings_yet)
                    setTextColor(0xFF888888.toInt())
                }
            )
            return
        }

        mappings.values.forEach { mapping ->
            val row = ItemMappingRowBinding.inflate(layoutInflater, binding.mappingsList, false)
            val channelName = channels.firstOrNull { it.key == mapping.channelId }?.name ?: mapping.channelId
            val sourceName = sources.firstOrNull { it.first == mapping.epgSourceId }?.second ?: mapping.epgSourceId
            val guideName = PlaylistRepository.channelNamesForSource(mapping.epgSourceId)[mapping.epgChannelId]
                ?: mapping.epgChannelId
            row.mappingText.text = getString(R.string.mapping_row_format, channelName, sourceName, guideName)
            row.removeMappingButton.setOnClickListener {
                EpgMappingStore.clearMapping(this, mapping.channelId)
                refreshMappingsList()
            }
            binding.mappingsList.addView(row.root)
        }
    }
}
