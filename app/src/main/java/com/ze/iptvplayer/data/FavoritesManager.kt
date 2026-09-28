package com.ze.iptvplayer.data

import android.content.Context

/** Persists the set of favorited channel ids. */
object FavoritesManager {
    private const val PREFS_NAME = "iptv_favorites"
    private const val KEY_FAVORITES = "favorite_ids"

    fun getAll(context: Context): Set<String> =
        prefs(context).getStringSet(KEY_FAVORITES, emptySet()) ?: emptySet()

    fun isFavorite(context: Context, channelId: String): Boolean =
        getAll(context).contains(channelId)

    /** Returns the new favorite state (true = now favorited). */
    fun toggle(context: Context, channelId: String): Boolean {
        val current = getAll(context).toMutableSet()
        val nowFavorite = if (current.contains(channelId)) {
            current.remove(channelId)
            false
        } else {
            current.add(channelId)
            true
        }
        prefs(context).edit().putStringSet(KEY_FAVORITES, current).apply()
        return nowFavorite
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
