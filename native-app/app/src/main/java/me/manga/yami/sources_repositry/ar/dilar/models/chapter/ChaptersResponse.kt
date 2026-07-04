package me.manga.yamiapk.sources_repositry.ar.dilar.models.chapter

import kotlinx.serialization.Serializable


@Serializable
data class ChaptersResponse(
    val memberShelves: MemberShelves? = MemberShelves(),
    val releases: List<Release?>? = listOf()
)