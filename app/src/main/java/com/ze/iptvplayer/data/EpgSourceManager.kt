package com.ze.iptvplayer.data

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.ze.iptvplayer.model.EpgSource
import java.util.UUID

/**
 * Persists user-added extra EPG (XMLTV) sources, beyond whatever came bundled with the
 * playlist itself. See PRIMARY_SOURCE_ID for how the bundled one is referenced.
 */
object EpgSourceManager {
    /** Reserved id for the FIRST EPG URL bundled with the playlist/Xtream config (not stored here).
     *  An M3U can declare more than one (comma-separated in its url-tvg header); each extra one
     *  beyond the first gets its own id, "primary:1", "primary:2", ... — see [primarySourceIdAt].
     *  Kept unchanged for index 0 so existing per-channel mappings/settings saved by earlier
     *  versions (back when there was only ever one primary source) still resolve correctly. */
    const val PRIMARY_SOURCE_ID = "primary"

    /** The source id for the playlist-bundled EPG URL at [index] (0 = the first/only one). */
    fun primarySourceIdAt(index: Int): String = if (index == 0) PRIMARY_SOURCE_ID else "$PRIMARY_SOURCE_ID:$index"

    /** The index [primarySourceIdAt] would need to reproduce [sourceId], or -1 if it doesn't name
     *  a playlist-bundled source at all (i.e. it's a user-added extra source's id). */
    fun primaryIndexOf(sourceId: String): Int {
        if (sourceId == PRIMARY_SOURCE_ID) return 0
        if (sourceId.startsWith("$PRIMARY_SOURCE_ID:")) {
            val index = sourceId.substring(PRIMARY_SOURCE_ID.length + 1).toIntOrNull()
            if (index != null && index > 0) return index
        }
        return -1
    }

    /** True if [sourceId] names one of the playlist-bundled sources (as opposed to a user-added
     *  extra one) — used to hide the "remove" action for it in Settings. */
    fun isPrimarySourceId(sourceId: String): Boolean = primaryIndexOf(sourceId) >= 0

    private const val PREFS_NAME = "iptv_epg_sources"
    private const val KEY_SOURCES = "sources_json"

    private val gson = Gson()
    private val listType = object : TypeToken<List<EpgSource>>() {}.type

    fun getAll(context: Context): List<EpgSource> {
        val json = prefs(context).getString(KEY_SOURCES, null) ?: return emptyList()
        return try {
            gson.fromJson(json, listType) ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun add(context: Context, name: String, url: String): EpgSource {
        val source = EpgSource(id = UUID.randomUUID().toString(), name = name, url = url)
        val updated = getAll(context) + source
        save(context, updated)
        return source
    }

    fun remove(context: Context, id: String) {
        val updated = getAll(context).filterNot { it.id == id }
        save(context, updated)
        // A mapping pointing at a now-gone source would silently resolve to nothing; drop it so
        // the channel falls back to normal resolution instead of a dead, invisible override.
        EpgMappingStore.clearMappingsForSource(context, id)
        // Same idea for a dangling "active source" override — removing the forced source should
        // fall back to normal (every-source) resolution, not silently match nothing at all.
        if (getActiveOverride(context) == id) setActiveOverride(context, null)
    }

    private fun save(context: Context, sources: List<EpgSource>) {
        prefs(context).edit().putString(KEY_SOURCES, gson.toJson(sources)).apply()
    }

    // ---- Active source override -----------------------------------------------------------

    private const val KEY_ACTIVE_SOURCE_ID = "active_source_id"

    /** When set, automatic EPG matching (see PlaylistRepository.resolve) considers ONLY this one
     *  source instead of trying every loaded source in order. Null means the default behavior
     *  (try every source). Per-channel mappings still override this regardless. */
    fun getActiveOverride(context: Context): String? = prefs(context).getString(KEY_ACTIVE_SOURCE_ID, null)

    /** Forces automatic matching to use only [sourceId] (or, with null, reverts to the default of
     *  trying every loaded source in priority order). Activating one source implicitly deactivates
     *  whichever was active before. */
    fun setActiveOverride(context: Context, sourceId: String?) {
        prefs(context).edit().putString(KEY_ACTIVE_SOURCE_ID, sourceId).apply()
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
