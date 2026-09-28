package com.ze.iptvplayer.data

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.ze.iptvplayer.model.EpgChannelMapping

/** Persists TiviMate-style per-channel overrides: "use guide channel X from source Y for channel Z". */
object EpgMappingStore {
    private const val PREFS_NAME = "iptv_epg_mappings"
    private const val KEY_MAPPINGS = "mappings_json"

    private val gson = Gson()
    private val mapType = object : TypeToken<Map<String, EpgChannelMapping>>() {}.type

    fun getAll(context: Context): Map<String, EpgChannelMapping> {
        val json = prefs(context).getString(KEY_MAPPINGS, null) ?: return emptyMap()
        return try {
            gson.fromJson(json, mapType) ?: emptyMap()
        } catch (e: Exception) {
            emptyMap()
        }
    }

    fun getMapping(context: Context, channelId: String): EpgChannelMapping? = getAll(context)[channelId]

    fun setMapping(context: Context, mapping: EpgChannelMapping) {
        val updated = getAll(context).toMutableMap()
        updated[mapping.channelId] = mapping
        save(context, updated)
    }

    fun clearMapping(context: Context, channelId: String) {
        val updated = getAll(context).toMutableMap()
        updated.remove(channelId)
        save(context, updated)
    }

    /** Removes every mapping that points at [sourceId] — call when that source is being removed,
     *  so no channel is left pointing at a guide that no longer exists. */
    fun clearMappingsForSource(context: Context, sourceId: String) {
        val updated = getAll(context).filterNot { it.value.epgSourceId == sourceId }
        save(context, updated)
    }

    private fun save(context: Context, mappings: Map<String, EpgChannelMapping>) {
        prefs(context).edit().putString(KEY_MAPPINGS, gson.toJson(mappings)).apply()
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
