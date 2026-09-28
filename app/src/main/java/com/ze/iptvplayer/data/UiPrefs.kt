package com.ze.iptvplayer.data

import android.content.Context

enum class ChannelViewMode { LIST, GRID }
enum class LongPressAction { SHOW_MENU, QUICK_FAVORITE }

/** Persists the user's preferred default channel view (list/grid) and long-press behavior. */
object UiPrefs {
    private const val PREFS_NAME = "iptv_ui_prefs"
    private const val KEY_VIEW_MODE = "view_mode"
    private const val KEY_LONG_PRESS_ACTION = "long_press_action"

    fun getDefaultViewMode(context: Context): ChannelViewMode {
        val raw = prefs(context).getString(KEY_VIEW_MODE, null)
        return runCatching { ChannelViewMode.valueOf(raw ?: "") }.getOrDefault(ChannelViewMode.LIST)
    }

    fun setDefaultViewMode(context: Context, mode: ChannelViewMode) {
        prefs(context).edit().putString(KEY_VIEW_MODE, mode.name).apply()
    }

    fun getLongPressAction(context: Context): LongPressAction {
        val raw = prefs(context).getString(KEY_LONG_PRESS_ACTION, null)
        return runCatching { LongPressAction.valueOf(raw ?: "") }.getOrDefault(LongPressAction.SHOW_MENU)
    }

    fun setLongPressAction(context: Context, action: LongPressAction) {
        prefs(context).edit().putString(KEY_LONG_PRESS_ACTION, action.name).apply()
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
