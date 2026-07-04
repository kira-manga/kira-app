package me.manga.yamiapk.sources_repositry.ar.dilar.models.chapter

import kotlinx.serialization.Serializable

@Serializable
data class Uploader(
    val id: Int? = 0,
    val nick: String? = "",
    val rev_links: List<String?>? = listOf()
)