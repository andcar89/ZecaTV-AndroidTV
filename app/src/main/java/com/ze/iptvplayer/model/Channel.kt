package com.ze.iptvplayer.model

/**
 * Which of the three sidebar sections a channel entry belongs to. Most providers only ship LIVE
 * channels, but some fold movies and/or TV show episodes into the same list/portal alongside live
 * channels, distinguished only by their group-title or file naming — see ContentTypeClassifier,
 * which is what actually assigns this field when a playlist/portal is parsed.
 */
enum class ContentType {
    LIVE, MOVIE, SERIES
}

/**
 * A single playable channel, whether it came from an M3U playlist or an Xtream portal.
 *
 * [id] is used to match this channel against EPG data (tvg-id in M3U, epg_channel_id in Xtream).
 * Providers routinely give the SAME id to multiple quality variants of one channel (e.g. "RTP 1",
 * "RTP 1 HD", "RTP 1 Full HD" all sharing one tvg-id) so they all resolve to the same guide entry —
 * that's correct for EPG purposes, but means [id] must NEVER be used to identify a specific channel
 * entry (favorites, per-channel EPG mapping overrides, "which channel is this", etc). Use [key] for
 * all of that instead — it's the channel's stream URL, which is unique per entry.
 */
data class Channel(
    val id: String,
    val key: String,
    val name: String,
    val streamUrl: String,
    val logoUrl: String?,
    val groupTitle: String,
    val streamId: String? = null,
    val contentType: ContentType = ContentType.LIVE
) {
    companion object {
        /**
         * Sentinel groupTitle used when a channel's source doesn't specify a real category.
         * Never shown raw — PlaylistRepository.categories() maps it to a localized display label.
         * Kept distinct from the synthetic "All channels" aggregate category to avoid the two
         * showing up as separate, confusingly-similar sidebar entries.
         */
        const val UNGROUPED_GROUP_KEY = "__ungrouped__"
    }
}
