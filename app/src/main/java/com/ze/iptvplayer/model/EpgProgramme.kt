package com.ze.iptvplayer.model

data class EpgProgramme(
    val channelId: String,
    val title: String,
    val description: String?,
    val startMillis: Long,
    val stopMillis: Long
)
