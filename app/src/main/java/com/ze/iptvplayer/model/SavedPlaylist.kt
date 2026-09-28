package com.ze.iptvplayer.model

import android.net.Uri

/**
 * One saved, named playlist source. The app can hold several; exactly one is "active" (its
 * channels loaded and browsable) at a time — see Prefs.activePlaylistId. Favorites and EPG data
 * (sources, mappings, the active-source override) are shared across every saved playlist, keyed
 * by [Channel.key] (its stream URL, already globally unique), so switching between playlists
 * never loses a favorite or a channel-EPG mapping. Only [lastCategoryId] is per-playlist — the
 * category shown while this one was last active, restored when you switch back to it.
 */
data class SavedPlaylist(
    val id: String,
    val name: String,
    val source: PlaylistSource,
    val lastCategoryId: String? = null
)

/** Suggests a sensible default name for a playlist source, used when the user leaves the name field blank. */
object PlaylistNaming {

    fun suggest(source: PlaylistSource): String = when (source) {
        is PlaylistSource.M3uUrl -> tryGetHost(source.url) ?: "M3U Playlist"
        is PlaylistSource.M3uFile -> tryGetFileName(source.filePath) ?: "M3U Playlist"
        is PlaylistSource.Xtream -> source.server.trim().ifBlank { "Xtream Playlist" }
    }

    private fun tryGetHost(url: String): String? = try {
        Uri.parse(url.trim()).host?.takeIf { it.isNotBlank() }
    } catch (e: Exception) {
        null
    }

    /** [PlaylistSource.M3uFile.filePath] is a content:// URI string on Android, not a filesystem
     *  path, so its last path segment is the closest equivalent to a file name (same convention
     *  AddSourceActivity already uses to display a picked file). */
    private fun tryGetFileName(path: String): String? {
        val trimmed = path.trim()
        if (trimmed.isEmpty()) return null
        val rawName = try {
            Uri.parse(trimmed).lastPathSegment
        } catch (e: Exception) {
            null
        } ?: trimmed.substringAfterLast('/').substringAfterLast('\\')
        val dot = rawName.lastIndexOf('.')
        val nameOnly = if (dot > 0) rawName.substring(0, dot) else rawName // dot>0, not >=0: a leading-dot name isn't emptied
        return nameOnly.ifBlank { null }
    }
}
