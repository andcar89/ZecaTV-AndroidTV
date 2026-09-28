package com.ze.iptvplayer.data

import android.content.Context
import android.net.Uri
import com.ze.iptvplayer.R
import com.ze.iptvplayer.model.Channel
import com.ze.iptvplayer.model.ContentType
import com.ze.iptvplayer.model.EpgProgramme
import com.ze.iptvplayer.model.PlaylistSource
import com.ze.iptvplayer.parser.EpgParseResult
import com.ze.iptvplayer.parser.EpgXmlParser
import com.ze.iptvplayer.parser.M3uParser
import com.ze.iptvplayer.parser.XtreamClient
import com.ze.iptvplayer.network.LenientTls
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.ByteArrayInputStream

/**
 * Simple in-memory singleton holding the currently loaded channel list and EPG data.
 * Good enough for a single-user TV app; swap for a Room-backed cache later if you want
 * offline reuse across process restarts without re-fetching.
 *
 * EPG data is kept per-source (see EpgSourceManager): the "primary" source is whatever
 * came bundled with the playlist/Xtream config, and any number of extra XMLTV sources can
 * be added in Settings. Each channel resolves to a (sourceId, epgChannelId) pair either via
 * an explicit EpgMappingStore override or, by default, its own id inside the primary source.
 */
object PlaylistRepository {

    const val CATEGORY_ALL = "__all__"
    const val CATEGORY_FAVORITES = "__favorites__"

    private data class EpgSourceData(
        val programmesByChannelId: Map<String, List<EpgProgramme>>,
        val channelNames: Map<String, String>,
        /** normalized display-name -> epgChannelId, precomputed once at load time (not per
         *  lookup) so the fuzzy-match fallback in resolve() stays a cheap hashmap lookup even
         *  with hundreds of channels. First occurrence wins on a collision. */
        val normalizedNameIndex: Map<String, String>
    )

    var channels: List<Channel> = emptyList()
        private set

    /** EPG URL(s) bundled with the current playlist source (used as the "primary" source(s)) —
     *  usually one, but an M3U can declare several as a comma-separated list (see EpgSourceManager). */
    private var primaryEpgUrls: List<String> = emptyList()

    private val epgDataBySource = mutableMapOf<String, EpgSourceData>()

    private val httpClient = LenientTls.newClientBuilder().build()

    suspend fun load(context: Context, source: PlaylistSource): Result<List<Channel>> = withContext(Dispatchers.IO) {
        try {
            var epgUrls: List<String> = emptyList()
            val list = when (source) {
                is PlaylistSource.M3uUrl -> {
                    val request = Request.Builder().url(source.url).build()
                    val result = httpClient.newCall(request).execute().use { resp ->
                        if (!resp.isSuccessful) throw java.io.IOException("HTTP ${resp.code}")
                        M3uParser.parse(resp.body!!.byteStream())
                    }
                    // Explicit EPG URL(s) the user typed in win entirely; otherwise fall back to
                    // whatever the playlist itself declares (url-tvg / x-tvg-url / tvg-url), which
                    // may itself be more than one, comma-separated (see M3uParser.splitEpgUrls).
                    val manualUrls = M3uParser.splitEpgUrls(source.epgUrl)
                    epgUrls = manualUrls.ifEmpty { result.epgUrls }
                    result.channels
                }
                is PlaylistSource.M3uFile -> {
                    val uri = Uri.parse(source.filePath)
                    val result = context.contentResolver.openInputStream(uri)?.use { stream ->
                        M3uParser.parse(stream)
                    } ?: throw java.io.IOException(context.getString(R.string.error_could_not_open_file))
                    val manualUrls = M3uParser.splitEpgUrls(source.epgUrl)
                    epgUrls = manualUrls.ifEmpty { result.epgUrls }
                    result.channels
                }
                is PlaylistSource.Xtream -> {
                    val xtream = XtreamClient(source.server, source.username, source.password)
                    epgUrls = listOf(xtream.epgUrl())
                    xtream.fetchLiveChannels()
                }
            }
            channels = list
            primaryEpgUrls = epgUrls

            reloadAllEpg(context)

            Result.success(list)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /** Re-fetches every primary EPG (if any) plus every user-added extra source. Best-effort per source. */
    suspend fun reloadAllEpg(context: Context) = withContext(Dispatchers.IO) {
        primaryEpgUrls.forEachIndexed { index, url ->
            loadEpgSource(EpgSourceManager.primarySourceIdAt(index), url)
        }
        EpgSourceManager.getAll(context).forEach { source ->
            loadEpgSource(source.id, source.url)
        }
    }

    /** (Re)loads a single EPG source by id. Safe to call for the primary id or any extra source id. */
    suspend fun reloadEpgSource(sourceId: String, url: String) = withContext(Dispatchers.IO) {
        loadEpgSource(sourceId, url)
    }

    private val epgLoadErrors = mutableMapOf<String, String>()

    private fun loadEpgSource(sourceId: String, url: String) {
        try {
            val request = Request.Builder().url(url).build()
            httpClient.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) {
                    epgLoadErrors[sourceId] = "HTTP ${resp.code}"
                    android.util.Log.e("PlaylistRepository", "EPG fetch failed for $url: HTTP ${resp.code}")
                    return
                }
                val bytes = resp.body!!.bytes()
                val isGzip = url.endsWith(".gz") ||
                    (bytes.size > 2 && bytes[0] == 0x1f.toByte() && bytes[1] == 0x8b.toByte())
                val result: EpgParseResult = EpgXmlParser.parse(ByteArrayInputStream(bytes), isGzip)
                val nameIndex = mutableMapOf<String, String>()
                result.channelNames.forEach { (epgChannelId, displayName) ->
                    val normalized = normalizeChannelName(displayName)
                    if (normalized.isNotEmpty()) nameIndex.putIfAbsent(normalized, epgChannelId)
                }
                epgDataBySource[sourceId] = EpgSourceData(result.programmesByChannelId, result.channelNames, nameIndex)
                epgLoadErrors.remove(sourceId)
            }
        } catch (e: Exception) {
            // Leave any previously loaded data for this source in place rather than wiping it,
            // but DO record what went wrong instead of failing silently — a source that looks
            // identical whether it's "never tried" or "actively broken" is very hard to debug.
            epgLoadErrors[sourceId] = e.javaClass.simpleName + (e.message?.let { ": $it" } ?: "")
            android.util.Log.e("PlaylistRepository", "EPG parse failed for $url", e)
        }
    }

    /** Ids + display names of every EPG source currently known (primary one(s) first, if any). */
    fun availableEpgSources(context: Context): List<Pair<String, String>> {
        val result = mutableListOf<Pair<String, String>>()
        primaryEpgUrls.forEachIndexed { index, _ ->
            val label = if (index == 0) {
                context.getString(R.string.label_provider_epg_included)
            } else {
                context.getString(R.string.label_provider_epg_included_numbered, index + 1)
            }
            result.add(EpgSourceManager.primarySourceIdAt(index) to label)
        }
        EpgSourceManager.getAll(context).forEach { result.add(it.id to it.name) }
        return result
    }

    /** id -> display-name of every guide channel known inside one EPG source (empty if not loaded yet). */
    fun channelNamesForSource(sourceId: String): Map<String, String> =
        epgDataBySource[sourceId]?.channelNames ?: emptyMap()

    /**
     * Diagnostic snapshot of one EPG source's load state: whether it's been fetched at all, how
     * many guide channels it defines, and how many total programme entries across all of them.
     * Lets the Settings screen show something concrete ("Loaded — 42 channels, 3,180 programmes")
     * instead of a source silently doing nothing when a feed turns out to be malformed/empty.
     */
    data class EpgSourceStatus(val isLoaded: Boolean, val channelCount: Int, val programmeCount: Int, val errorMessage: String?)

    fun epgSourceStatus(sourceId: String): EpgSourceStatus {
        val data = epgDataBySource[sourceId]
        val error = epgLoadErrors[sourceId]
        if (data == null) return EpgSourceStatus(false, 0, 0, error)
        val programmeCount = data.programmesByChannelId.values.sumOf { it.size }
        return EpgSourceStatus(true, data.channelNames.size, programmeCount, error)
    }

    private fun hasProgrammeData(sourceId: String, epgChannelId: String): Boolean =
        epgDataBySource[sourceId]?.programmesByChannelId?.get(epgChannelId)?.isNotEmpty() == true

    /** When set (see [setActiveEpgSourceOverride]), the id of the one source that automatic
     *  matching is forced to use — every other loaded source is ignored for matching purposes
     *  until this is cleared. Per-channel mappings still take priority over this. */
    fun activeEpgSourceOverride(context: Context): String? = EpgSourceManager.getActiveOverride(context)

    /** Forces automatic matching to use only [sourceId] (or, with null, reverts to the default of
     *  trying every loaded source in priority order). "Activate"/"Deactivate" in Settings —
     *  activating one source implicitly deactivates whichever was active before. */
    fun setActiveEpgSourceOverride(context: Context, sourceId: String?) =
        EpgSourceManager.setActiveOverride(context, sourceId)

    /**
     * Resolves which (sourceId, epgChannelId) a playlist channel should use, honoring any user
     * mapping. Absent an explicit mapping, this checks EVERY loaded EPG source (primary first,
     * then any extras added in Settings) — or, if an active source override is set, ONLY that one
     * source — in three passes: first an exact tvg-id match, then a fuzzy match on normalized
     * channel *name*, then, only as a last resort, a looser abbreviation match. The name-matching
     * passes exist because a playlist's tvg-id scheme and a third-party EPG's channel-id scheme
     * are two unrelated providers' conventions with no reason to line up as strings, even when the
     * channels obviously refer to the same thing to a human ("PT - RTP 1" vs "RTP1.pt").
     */
    private fun resolve(context: Context, channel: Channel): Pair<String, String>? {
        val mapping = EpgMappingStore.getMapping(context, channel.key)
        if (mapping != null) return mapping.epgSourceId to mapping.epgChannelId

        // An active override restricts matching to just that one source — including the fallback
        // at the bottom of this method — rather than the normal primary-then-extras priority order.
        val overrideSourceId = EpgSourceManager.getActiveOverride(context)
        val sourceIds = if (overrideSourceId != null) {
            listOf(overrideSourceId)
        } else {
            primaryEpgUrls.indices.map { EpgSourceManager.primarySourceIdAt(it) } +
                EpgSourceManager.getAll(context).map { it.id }
        }

        for (sourceId in sourceIds) {
            if (hasProgrammeData(sourceId, channel.id)) return sourceId to channel.id
        }

        val normalizedChannelName = normalizeChannelName(channel.name)
        if (normalizedChannelName.isNotEmpty()) {
            for (sourceId in sourceIds) {
                val epgChannelId = epgDataBySource[sourceId]?.normalizedNameIndex?.get(normalizedChannelName)
                if (epgChannelId != null) return sourceId to epgChannelId
            }

            // Last resort: a heavily abbreviated guide name (e.g. "SPT1HD" for "SPORT TV 1 FHD") —
            // see matchesAbbreviation for exactly what this does and does not accept.
            for (sourceId in sourceIds) {
                val candidates = epgDataBySource[sourceId]?.normalizedNameIndex ?: continue
                for ((candidateNormalizedName, epgChannelId) in candidates) {
                    if (matchesAbbreviation(normalizedChannelName, candidateNormalizedName)) {
                        return sourceId to epgChannelId
                    }
                }
            }
        }

        // Nothing confirmed anywhere — fall back to the active override (if set) or the first
        // primary source (if one exists at all), in case it simply hasn't finished loading yet.
        if (overrideSourceId != null) return overrideSourceId to channel.id
        if (primaryEpgUrls.isEmpty()) return null
        return EpgSourceManager.PRIMARY_SOURCE_ID to channel.id
    }

    private val qualitySuffixRegex = Regex("(hd|fhd|fullhd|uhd|4k|sd|hevc)$")

    /**
     * Reduces a channel name to just its "identity" for fuzzy matching: lowercase, strip a
     * leading country/provider prefix like "PT: " or "PT - ", strip common quality/resolution
     * tokens (HD, Full HD, 4K, etc.), then strip everything but letters and digits. Turns both
     * "PT - RTP 1" and "RTP1.pt"'s display name "RTP 1" into the same "rtp1", regardless of
     * which provider's formatting convention produced either string.
     */
    private fun normalizeChannelName(name: String): String {
        var n = name.lowercase()
        n = n.replace(Regex("^[a-z]{2,4}\\s*[:\\-]\\s*"), "")
        n = n.replace(Regex("\\b(hd|fhd|full ?hd|uhd|4k|sd|hevc)\\b"), "")
        n = n.replace(Regex("[^a-z0-9]"), "")
        // The word-boundary regex above needs a boundary before the tag, which a digit right next
        // to it (no space) doesn't provide — "SPT1HD" has no boundary before "hd". This second,
        // narrower pass only looks at the very end of the string, so it catches that glued-on case too.
        n = qualitySuffixRegex.replace(n, "")
        return n
    }

    private val trailingDigitsRegex = Regex("(\\d+)$")

    /**
     * Last-resort, deliberately narrow match for a heavily abbreviated guide name against a
     * normalized channel name — e.g. a channel "SPORT TV 1 FHD" (normalizes to "sporttv1")
     * against a guide entry "SPT1HD" (normalizes to "spt1"). Unlike [normalizeChannelName]'s
     * prefix/quality stripping, collapsing whole words and vowels down to a call-sign-style
     * abbreviation has no reliable inverse — this is a genuine guess, not a rule. The guards below
     * (agreement on any trailing channel number, and a minimum length ratio between the two names,
     * stricter when neither has a number to anchor on) cut the worst false-positive risk — a short,
     * generic abbreviation coincidentally fitting inside an unrelated long name — but cannot
     * eliminate it. When this gets a channel wrong, "Assign channel EPG" fixes it permanently for
     * that one channel.
     */
    private fun matchesAbbreviation(a: String, b: String): Boolean {
        val (lettersA, digitsA) = splitTrailingDigits(a)
        val (lettersB, digitsB) = splitTrailingDigits(b)
        if (lettersA.isEmpty() || lettersB.isEmpty()) return false

        // A channel number is the strongest signal available here — if both sides have one, a
        // mismatch (e.g. "...1" vs "...2") rules the pair out immediately.
        if (digitsA.isNotEmpty() && digitsB.isNotEmpty() && digitsA != digitsB) return false
        val hasNumberAnchor = digitsA.isNotEmpty() && digitsB.isNotEmpty()

        val (shorter, longer) = if (lettersA.length <= lettersB.length) lettersA to lettersB else lettersB to lettersA
        if (shorter.length < 3) return false

        // Without a shared number to anchor on, a loose subsequence match alone is too easy to
        // satisfy by coincidence, so it's held to a tighter length ratio.
        val minRatio = if (hasNumberAnchor) 0.4 else 0.55
        if (shorter.length.toDouble() / longer.length < minRatio) return false

        return isSubsequence(shorter, longer)
    }

    private fun splitTrailingDigits(normalized: String): Pair<String, String> {
        val match = trailingDigitsRegex.find(normalized)
        return if (match != null) normalized.substring(0, match.range.first) to match.value else normalized to ""
    }

    /** True if every character of [shorter] appears in [longer], in order (not necessarily contiguous). */
    private fun isSubsequence(shorter: String, longer: String): Boolean {
        var i = 0
        for (c in longer) {
            if (i < shorter.length && c == shorter[i]) i++
        }
        return i == shorter.length
    }

    private fun programmesForResolved(sourceId: String, epgChannelId: String): List<EpgProgramme> =
        epgDataBySource[sourceId]?.programmesByChannelId?.get(epgChannelId) ?: emptyList()

    /**
     * Index of the first programme in [programmes] with startMillis > [time], or programmes.size
     * if none. Binary search — safe ONLY because [programmes] is guaranteed chronologically
     * sorted by EpgXmlParser (see its stop-time-inference step, which sorts before returning) and
     * there's exactly one place that ever populates programmesByChannelId, from that parser's
     * output directly. If that ever changes, this silently returns wrong answers rather than
     * crashing — don't break the sort without updating this.
     */
    private fun upperBound(programmes: List<EpgProgramme>, time: Long): Int {
        var lo = 0
        var hi = programmes.size
        while (lo < hi) {
            val mid = (lo + hi) / 2
            if (programmes[mid].startMillis <= time) lo = mid + 1 else hi = mid
        }
        return lo
    }

    /** Current and next programme for a channel, or (null, null) if there's no EPG data for it. */
    fun nowNext(context: Context, channel: Channel): Pair<EpgProgramme?, EpgProgramme?> {
        val (sourceId, epgChannelId) = resolve(context, channel) ?: return null to null
        val programmes = programmesForResolved(sourceId, epgChannelId)
        if (programmes.isEmpty()) return null to null
        val now = System.currentTimeMillis()
        val idx = upperBound(programmes, now)
        val next = programmes.getOrNull(idx)
        val candidateCurrent = programmes.getOrNull(idx - 1)
        val current = if (candidateCurrent != null && now < candidateCurrent.stopMillis) candidateCurrent else null
        return current to next
    }

    /** Programmes for a channel that overlap [windowStart, windowEnd), sorted by start time. Used by the EPG grid. */
    fun programmesFor(context: Context, channel: Channel, windowStart: Long, windowEnd: Long): List<EpgProgramme> {
        val (sourceId, epgChannelId) = resolve(context, channel) ?: return emptyList()
        val programmes = programmesForResolved(sourceId, epgChannelId)
        if (programmes.isEmpty()) return emptyList()
        // Binary search to the last programme starting at or before windowStart (it may still
        // overlap the window even though it started earlier), then scan forward only as far as
        // the window actually extends — cheap even when a channel's full guide spans many days,
        // versus a full linear scan touching every entry regardless of window size.
        val startIdx = (upperBound(programmes, windowStart) - 1).coerceAtLeast(0)
        val result = mutableListOf<EpgProgramme>()
        for (i in startIdx until programmes.size) {
            val p = programmes[i]
            if (p.startMillis >= windowEnd) break
            if (p.stopMillis > windowStart) result.add(p)
        }
        return result
    }

    /** Channels of [contentType] only, grouped by their group-title — defaults to LIVE so every
     *  existing caller (EPG grid, search, per-channel mapping, the player's own sidebars) keeps
     *  browsing live channels exactly as before without needing to know sections exist at all. */
    fun channelsByGroup(contentType: ContentType = ContentType.LIVE): Map<String, List<Channel>> =
        channels.filter { it.contentType == contentType }.groupBy { it.groupTitle }

    /** True if the current playlist has at least one entry classified as [contentType] — used to
     *  hide the Movies/TV Shows sidebar tabs entirely when a provider doesn't mix any in. */
    fun hasContentType(contentType: ContentType): Boolean = channels.any { it.contentType == contentType }

    /** Ids + display labels for a sidebar category tree: Favorites (if any) / All / each group,
     *  scoped to one section ([contentType]). */
    fun categories(context: Context, contentType: ContentType = ContentType.LIVE): List<Pair<String, String>> {
        val list = mutableListOf<Pair<String, String>>()
        val favoriteIds = FavoritesManager.getAll(context)
        if (channels.any { it.contentType == contentType && it.key in favoriteIds }) {
            list.add(CATEGORY_FAVORITES to context.getString(R.string.category_favorites))
        }
        list.add(CATEGORY_ALL to context.getString(R.string.category_all_channels))
        // Deliberately NOT alphabetized — channelsByGroup() is a groupBy, which preserves each
        // group's first-occurrence order from the channel list, which itself follows the M3U
        // file's own channel order. Zé arranges categories in a specific order in his M3U editor
        // before exporting, and the sidebar should reflect that instead of re-sorting it away
        // (same fix already made on the Windows build).
        channelsByGroup(contentType).keys.forEach { group ->
            val label = if (group == Channel.UNGROUPED_GROUP_KEY) {
                context.getString(R.string.category_uncategorized)
            } else {
                group
            }
            list.add(group to label)
        }
        return list
    }

    /** Resolves a category id (as returned by [categories]) to its channel list, scoped to [contentType]. */
    fun channelsForCategory(context: Context, id: String, contentType: ContentType = ContentType.LIVE): List<Channel> = when (id) {
        CATEGORY_FAVORITES -> {
            val favoriteIds = FavoritesManager.getAll(context)
            channels.filter { it.contentType == contentType && it.key in favoriteIds }
        }
        CATEGORY_ALL -> channels.filter { it.contentType == contentType }
        else -> channelsByGroup(contentType)[id] ?: emptyList()
    }
}
