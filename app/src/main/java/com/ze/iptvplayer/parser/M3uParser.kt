package com.ze.iptvplayer.parser

import com.ze.iptvplayer.model.Channel
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader

/**
 * Parses standard extended M3U playlists as used by IPTV services, e.g.:
 *
 * #EXTM3U url-tvg="http://server/epg.xml"
 * #EXTINF:-1 tvg-id="bbc1" tvg-logo="http://.../bbc1.png" group-title="UK",BBC One
 * http://server/live/user/pass/12345.ts
 */
object M3uParser {

    private val attrRegex = Regex("([a-zA-Z0-9_-]+)=\"([^\"]*)\"")

    /** [epgUrls] are whatever companion EPG URL(s) the playlist itself declares on its #EXTM3U
     *  header line (checking the "url-tvg", "x-tvg-url", and "tvg-url" attribute names — different
     *  generators use different ones), or empty if the file doesn't declare any. Some generators
     *  (e.g. the M3U Editor) pack several feeds into one attribute as a comma-separated list —
     *  "url1,url2,url3" — which is split back out here, so each URL ends up as its own
     *  independently-loaded EPG source; see EpgSourceManager/PlaylistRepository. */
    data class M3uParseResult(val channels: List<Channel>, val epgUrls: List<String>)

    /** Splits a comma-separated list of URLs (used by both the #EXTM3U header attribute and the
     *  manual "EPG URL" override field) into trimmed, non-empty entries. Also accepts a single URL. */
    fun splitEpgUrls(raw: String?): List<String> =
        raw?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList()

    fun parse(input: InputStream): M3uParseResult {
        val channels = mutableListOf<Channel>()
        var headerEpgUrls: List<String> = emptyList()
        BufferedReader(InputStreamReader(input, Charsets.UTF_8)).use { reader ->
            var line: String? = reader.readLine()
            var pendingName = ""
            var pendingLogo: String? = null
            var pendingGroup = Channel.UNGROUPED_GROUP_KEY
            var pendingTvgId = ""
            var index = 0

            while (line != null) {
                val trimmed = line.trim()
                when {
                    trimmed.startsWith("#EXTM3U", ignoreCase = true) -> {
                        val attrs = attrRegex.findAll(trimmed).associate { it.groupValues[1] to it.groupValues[2] }
                        val url = attrs["url-tvg"] ?: attrs["x-tvg-url"] ?: attrs["tvg-url"]
                        headerEpgUrls = splitEpgUrls(url)
                    }
                    trimmed.startsWith("#EXTINF", ignoreCase = true) -> {
                        val attrs = attrRegex.findAll(trimmed).associate { it.groupValues[1] to it.groupValues[2] }
                        pendingLogo = attrs["tvg-logo"]
                        pendingGroup = attrs["group-title"]?.takeIf { it.isNotBlank() } ?: Channel.UNGROUPED_GROUP_KEY
                        pendingTvgId = attrs["tvg-id"] ?: ""

                        val commaIdx = trimmed.lastIndexOf(',')
                        pendingName = if (commaIdx != -1 && commaIdx < trimmed.length - 1) {
                            trimmed.substring(commaIdx + 1).trim()
                        } else {
                            attrs["tvg-name"] ?: "Channel ${index + 1}"
                        }
                    }
                    trimmed.isNotEmpty() && !trimmed.startsWith("#") -> {
                        // Stream URL line — this closes out the channel we were building.
                        val id = pendingTvgId.ifBlank { "gen_${index}_${pendingName.hashCode()}" }
                        val resolvedName = pendingName.ifBlank { "Channel ${index + 1}" }
                        channels.add(
                            Channel(
                                id = id,
                                key = trimmed,
                                name = resolvedName,
                                streamUrl = trimmed,
                                logoUrl = pendingLogo,
                                groupTitle = pendingGroup,
                                contentType = ContentTypeClassifier.classify(pendingGroup, resolvedName, trimmed)
                            )
                        )
                        index++
                        pendingName = ""
                        pendingLogo = null
                        pendingGroup = Channel.UNGROUPED_GROUP_KEY
                        pendingTvgId = ""
                    }
                }
                line = reader.readLine()
            }
        }
        return M3uParseResult(channels, headerEpgUrls)
    }
}
