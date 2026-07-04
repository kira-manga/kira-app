package me.manga.yamiapk.sources_repositry.ru.desu.models.home

import kotlinx.serialization.Serializable

@Serializable
data class First(
    val ch: String? = "",
    val date: String? = "",
    val name: String? = "",
    val vol: String? = ""
)