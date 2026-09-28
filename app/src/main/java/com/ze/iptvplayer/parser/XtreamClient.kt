package com.ze.iptvplayer.parser

import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import com.ze.iptvplayer.model.Channel
import com.ze.iptvplayer.network.LenientTls
import okhttp3.Request

/**
 * Talks to a standard Xtream Codes panel via its player_api.php endpoint.
 * All methods here do blocking network I/O — call from a background thread/coroutine.
 */
class XtreamClient(
    private val server: String,
    private val username: String,
    private val password: String
) {
    private val client = LenientTls.newClientBuilder().build()
    private val gson = Gson()

    private fun baseUrl(): String {
        var s = server.trim()
        if (!s.startsWith("http://") && !s.startsWith("https://")) s = "http://$s"
        if (s.endsWith("/")) s = s.dropLast(1)
        return s
    }

    private data class LiveCategory(
        @SerializedName("category_id") val categoryId: String,
        @SerializedName("category_name") val categoryName: String
    )

    private data class LiveStream(
        @SerializedName("stream_id") val streamId: Int,
        @SerializedName("name") val name: String,
        @SerializedName("stream_icon") val streamIcon: String?,
        @SerializedName("category_id") val categoryId: String?,
        @SerializedName("epg_channel_id") val epgChannelId: String?
    )

    /** Fetches live categories + streams and builds a flat channel list with playable stream URLs. */
    fun fetchLiveChannels(): List<Channel> {
        val base = baseUrl()

        val categoriesJson = get("$base/player_api.php?username=$username&password=$password&action=get_live_categories")
        val categories = try {
            gson.fromJson(categoriesJson, Array<LiveCategory>::class.java).associateBy { it.categoryId }
        } catch (e: Exception) {
            emptyMap()
        }

        val streamsJson = get("$base/player_api.php?username=$username&password=$password&action=get_live_streams")
        val streams = try {
            gson.fromJson(streamsJson, Array<LiveStream>::class.java).toList()
        } catch (e: Exception) {
            emptyList()
        }

        return streams.map { s ->
            val group = categories[s.categoryId]?.categoryName ?: Channel.UNGROUPED_GROUP_KEY
            val streamUrl = "$base/live/$username/$password/${s.streamId}.ts"
            Channel(
                id = s.epgChannelId?.takeIf { it.isNotBlank() } ?: "xt_${s.streamId}",
                key = streamUrl,
                name = s.name,
                streamUrl = streamUrl,
                logoUrl = s.streamIcon,
                groupTitle = group,
                streamId = s.streamId.toString(),
                // Some Xtream panels dump movies/series into the same "live" category list
                // instead of exposing them through the separate VOD/series endpoints — same
                // group-title-based guess M3uParser makes, see ContentTypeClassifier.
                contentType = ContentTypeClassifier.classify(group, s.name, streamUrl)
            )
        }
    }

    /** URL of the full XMLTV EPG feed for this account (the server may gzip-encode the body). */
    fun epgUrl(): String = "${baseUrl()}/xmltv.php?username=$username&password=$password"

    private fun get(url: String): String {
        val request = Request.Builder().url(url).build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw java.io.IOException("HTTP ${response.code} for $url")
            return response.body?.string() ?: ""
        }
    }
}
