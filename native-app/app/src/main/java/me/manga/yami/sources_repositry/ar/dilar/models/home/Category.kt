package me.manga.yamiapk.sources_repositry.ar.dilar.models.home

import kotlinx.serialization.Serializable


@Serializable
data class Category(
    val icon: String? = "",
    val id: Int? = 0,
    val manga_id: Int? = 0,
    val name: String? = ""
)