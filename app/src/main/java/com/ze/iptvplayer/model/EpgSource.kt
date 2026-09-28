package com.ze.iptvplayer.model

/**
 * A named XMLTV feed. "primary" is reserved for whatever EPG URL came bundled with the
 * playlist itself (M3U's optional EPG URL, or Xtream's xmltv.php) — it's not stored here,
 * see EpgSourceManager.PRIMARY_SOURCE_ID. Everything in this model is a user-added extra.
 */
data class EpgSource(
    val id: String,
    val name: String,
    val url: String
)
