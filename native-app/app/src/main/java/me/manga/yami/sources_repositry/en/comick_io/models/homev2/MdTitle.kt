package me.manga.yamiapk.sources_repositry.en.comick_io.models.homev2

import kotlinx.serialization.Serializable

@Serializable

data class MdTitle(
    val lang: String? = "",
    val title: String? = ""
)