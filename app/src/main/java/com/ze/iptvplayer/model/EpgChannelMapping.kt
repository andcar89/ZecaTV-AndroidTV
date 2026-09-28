package com.ze.iptvplayer.model

/**
 * Overrides which EPG feed + which channel id within that feed to use for now/next and
 * the guide grid, for one specific playlist channel. Without a mapping, a channel defaults
 * to its own id looked up in the "primary" EPG source (see EpgSourceManager).
 */
data class EpgChannelMapping(
    /** A Channel's [Channel.key] (its stream URL) — NOT [Channel.id], which providers often share
     * across quality variants of the same channel and would incorrectly apply this override to all of them. */
    val channelId: String,
    val epgSourceId: String,
    val epgChannelId: String
)
