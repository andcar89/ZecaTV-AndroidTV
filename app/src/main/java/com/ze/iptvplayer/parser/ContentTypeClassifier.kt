package com.ze.iptvplayer.parser

import com.ze.iptvplayer.model.ContentType

/**
 * Guesses whether a playlist/portal entry is a live channel, a movie, or a TV show episode.
 *
 * Some providers ship a single flat list/portal that mixes all three together instead of exposing
 * them separately (movies and series folded into the same M3U as live channels, or dumped into the
 * Xtream "live" category list) — this is what actually splits them back apart for the Movies/TV
 * Shows sidebar tabs.
 *
 * Earlier this leaned on the group-title text ("Movies", "Séries", etc), which backfires badly:
 * plenty of providers name a *live channel* category that way too — e.g. "PT - Filmes/Séries"
 * full of always-on channels like "TvCine Action HD", not an actual on-demand catalog. So this
 * only trusts signals from the entry itself, never the group/category name:
 *  - A name ending in a quality tag (HD/FHD/HEVC/4K/SD) is a dead giveaway of a live channel — a
 *    real VOD catalog never lists "Movie Title HEVC" as its own separate browsable entry, the way
 *    a live provider lists the same channel in several qualities. This overrides everything else.
 *  - An actual season/episode tag in the name ("S01E02", "1x02") means a TV show episode.
 *  - A year in parentheses at the end of the name ("Movie Title (2021)") means a movie — the
 *    common way VOD catalogs title entries.
 *  - A VOD-typical file extension on the stream URL itself (live streams are almost always .ts/
 *    .m3u8 or extensionless) means a movie.
 *  - Otherwise, live — the safe default when nothing above says otherwise.
 */
object ContentTypeClassifier {

    private val qualitySuffixRegex = Regex(
        "\\b(hd|fhd|full ?hd|uhd|4k|sd|hevc)\\b\\.?$",
        RegexOption.IGNORE_CASE
    )
    // Matches "S01E02", "S1 E2", "1x02", "T01E02" — the common ways providers encode season/
    // episode into an episode's display name.
    private val episodeTagRegex = Regex(
        "\\b(s\\d{1,2}\\s?e\\d{1,3}|t\\d{1,2}\\s?e\\d{1,3}|\\d{1,2}x\\d{1,3})\\b",
        RegexOption.IGNORE_CASE
    )
    private val movieYearRegex = Regex("\\((19|20)\\d{2}\\)\\s*$")
    private val vodExtensionRegex = Regex(
        "\\.(mp4|mkv|avi|mov|m4v|wmv|flv)(\\?.*)?$",
        RegexOption.IGNORE_CASE
    )

    fun classify(groupTitle: String, name: String, streamUrl: String): ContentType {
        val trimmedName = name.trim()

        if (qualitySuffixRegex.containsMatchIn(trimmedName)) return ContentType.LIVE
        if (episodeTagRegex.containsMatchIn(trimmedName)) return ContentType.SERIES
        if (movieYearRegex.containsMatchIn(trimmedName)) return ContentType.MOVIE
        if (vodExtensionRegex.containsMatchIn(streamUrl)) return ContentType.MOVIE

        return ContentType.LIVE
    }
}
