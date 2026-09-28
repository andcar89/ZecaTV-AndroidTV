package com.ze.iptvplayer.data

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.ze.iptvplayer.model.PlaylistNaming
import com.ze.iptvplayer.model.PlaylistSource
import com.ze.iptvplayer.model.SavedPlaylist
import java.util.UUID

/**
 * Persists every saved playlist plus which one is active — the Android counterpart of Windows'
 * AppSettings.Playlists/ActivePlaylistId. Favorites, EPG sources/mappings and the EPG
 * active-source override are NOT here: they live in their own stores (FavoritesManager,
 * EpgSourceManager, EpgMappingStore), all keyed by [com.ze.iptvplayer.model.Channel.key], and are
 * shared across every saved playlist rather than scoped to one.
 */
object Prefs {
    private const val PREFS_NAME = "iptv_prefs"

    // Legacy single-playlist keys. Read only by [migrateSingleSourceIfNeeded] to migrate an older
    // install's prefs into KEY_PLAYLISTS_JSON the first time they're opened after upgrading; never
    // written to again afterward (removed once migrated).
    private const val KEY_SOURCE_TYPE = "source_type"
    private const val KEY_SOURCE_JSON = "source_json"

    private const val KEY_PLAYLISTS_JSON = "playlists_json"
    private const val KEY_ACTIVE_PLAYLIST_ID = "active_playlist_id"

    private val gson = Gson()

    // Gson can't deserialize the PlaylistSource sealed class polymorphically on its own, so each
    // playlist is stored as this flat envelope (a type tag + that type's own JSON) instead —
    // the same trick the pre-multi-playlist version of this file used for the single source.
    private data class PlaylistEnvelope(
        val id: String,
        val name: String,
        val sourceType: String,
        val sourceJson: String,
        val lastCategoryId: String?
    )

    private val envelopeListType = object : TypeToken<List<PlaylistEnvelope>>() {}.type

    private fun sourceTypeTag(source: PlaylistSource): String = when (source) {
        is PlaylistSource.M3uUrl -> "m3u_url"
        is PlaylistSource.M3uFile -> "m3u_file"
        is PlaylistSource.Xtream -> "xtream"
    }

    private fun decodeSource(type: String, json: String): PlaylistSource? = try {
        when (type) {
            "m3u_url" -> gson.fromJson(json, PlaylistSource.M3uUrl::class.java)
            "m3u_file" -> gson.fromJson(json, PlaylistSource.M3uFile::class.java)
            "xtream" -> gson.fromJson(json, PlaylistSource.Xtream::class.java)
            else -> null
        }
    } catch (e: Exception) {
        null
    }

    private fun toEnvelope(playlist: SavedPlaylist) = PlaylistEnvelope(
        id = playlist.id,
        name = playlist.name,
        sourceType = sourceTypeTag(playlist.source),
        sourceJson = gson.toJson(playlist.source),
        lastCategoryId = playlist.lastCategoryId
    )

    /** Null if the envelope's source can't be decoded (corrupt prefs) — dropped rather than crashing. */
    private fun fromEnvelope(envelope: PlaylistEnvelope): SavedPlaylist? {
        val source = decodeSource(envelope.sourceType, envelope.sourceJson) ?: return null
        return SavedPlaylist(envelope.id, envelope.name, source, envelope.lastCategoryId)
    }

    // ---- Playlists -----------------------------------------------------------------------

    fun getAllPlaylists(context: Context): List<SavedPlaylist> {
        migrateSingleSourceIfNeeded(context)
        val json = prefs(context).getString(KEY_PLAYLISTS_JSON, null) ?: return emptyList()
        return try {
            val envelopes: List<PlaylistEnvelope> = gson.fromJson(json, envelopeListType) ?: emptyList()
            envelopes.mapNotNull { fromEnvelope(it) }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun savePlaylists(context: Context, playlists: List<SavedPlaylist>) {
        prefs(context).edit().putString(KEY_PLAYLISTS_JSON, gson.toJson(playlists.map { toEnvelope(it) })).apply()
    }

    fun activePlaylistId(context: Context): String? {
        migrateSingleSourceIfNeeded(context)
        return prefs(context).getString(KEY_ACTIVE_PLAYLIST_ID, null)
    }

    fun setActivePlaylistId(context: Context, id: String?) {
        prefs(context).edit().putString(KEY_ACTIVE_PLAYLIST_ID, id).apply()
    }

    /** The currently active playlist, or null if none is configured yet (or the active id is
     *  stale — falls back to the first saved playlist rather than treating it as unconfigured). */
    fun activePlaylist(context: Context): SavedPlaylist? {
        val all = getAllPlaylists(context)
        return all.firstOrNull { it.id == activePlaylistId(context) } ?: all.firstOrNull()
    }

    /** Saves a brand-new playlist, makes it active, and returns it. */
    fun addPlaylist(context: Context, name: String, source: PlaylistSource): SavedPlaylist {
        val playlist = SavedPlaylist(
            id = UUID.randomUUID().toString(),
            name = name.ifBlank { PlaylistNaming.suggest(source) },
            source = source
        )
        savePlaylists(context, getAllPlaylists(context) + playlist)
        setActivePlaylistId(context, playlist.id)
        return playlist
    }

    /** Updates an existing playlist's name/source in place (its id and lastCategoryId are kept). */
    fun updatePlaylist(context: Context, id: String, name: String, source: PlaylistSource) {
        val updated = getAllPlaylists(context).map { existing ->
            if (existing.id == id) {
                existing.copy(name = name.ifBlank { PlaylistNaming.suggest(source) }, source = source)
            } else {
                existing
            }
        }
        savePlaylists(context, updated)
    }

    /** Removes a saved playlist. If it was active, falls back to another saved playlist (if any) —
     *  the caller is responsible for actually loading that new active playlist's channels. */
    fun removePlaylist(context: Context, id: String) {
        val remaining = getAllPlaylists(context).filterNot { it.id == id }
        savePlaylists(context, remaining)
        if (activePlaylistId(context) == id) {
            setActivePlaylistId(context, remaining.firstOrNull()?.id)
        }
    }

    /** Remembers which category was showing for [id], restored next time it becomes active again. */
    fun setLastCategoryForPlaylist(context: Context, id: String, categoryId: String) {
        val updated = getAllPlaylists(context).map { existing ->
            if (existing.id == id) existing.copy(lastCategoryId = categoryId) else existing
        }
        savePlaylists(context, updated)
    }

    /**
     * A prefs blob from before multi-playlist support has only the single legacy source. Wraps it
     * as the first (and active) saved playlist, once, so upgrading never loses the playlist
     * someone already has configured. Guarded by KEY_PLAYLISTS_JSON's mere presence (not whether
     * the list is non-empty) so this never re-runs once migrated, even after every playlist is
     * later removed.
     */
    private fun migrateSingleSourceIfNeeded(context: Context) {
        val p = prefs(context)
        if (p.contains(KEY_PLAYLISTS_JSON)) return

        val type = p.getString(KEY_SOURCE_TYPE, null)
        val json = p.getString(KEY_SOURCE_JSON, null)
        val legacySource = if (type != null && json != null) decodeSource(type, json) else null

        val playlists = if (legacySource != null) {
            listOf(SavedPlaylist(id = UUID.randomUUID().toString(), name = PlaylistNaming.suggest(legacySource), source = legacySource))
        } else {
            emptyList()
        }

        p.edit()
            .putString(KEY_PLAYLISTS_JSON, gson.toJson(playlists.map { toEnvelope(it) }))
            .putString(KEY_ACTIVE_PLAYLIST_ID, playlists.firstOrNull()?.id)
            .remove(KEY_SOURCE_TYPE)
            .remove(KEY_SOURCE_JSON)
            .apply()
    }

    fun clearSource(context: Context) {
        prefs(context).edit().clear().apply()
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
