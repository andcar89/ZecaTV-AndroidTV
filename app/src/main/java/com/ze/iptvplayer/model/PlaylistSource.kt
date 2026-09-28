package com.ze.iptvplayer.model

sealed class PlaylistSource {
    data class M3uUrl(val url: String, val epgUrl: String? = null) : PlaylistSource()
    data class M3uFile(val filePath: String, val epgUrl: String? = null) : PlaylistSource()
    data class Xtream(
        val server: String,
        val username: String,
        val password: String
    ) : PlaylistSource()
}
