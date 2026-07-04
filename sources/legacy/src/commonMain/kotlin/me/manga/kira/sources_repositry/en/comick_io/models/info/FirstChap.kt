package me.manga.kira.sources_repositry.en.comick_io.models.info

import kotlinx.serialization.Serializable

@Serializable
data class FirstChap(
    val chap: String? = null,
    val group_name: List<String>? = null,
    val hid: String? = null,
    val lang: String? = null,
)