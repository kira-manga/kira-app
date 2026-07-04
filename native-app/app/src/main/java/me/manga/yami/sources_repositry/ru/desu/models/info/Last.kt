package me.manga.yamiapk.sources_repositry.ru.desu.models.info

import kotlinx.serialization.Serializable

@Serializable
data class Last(
    val ch: String? = "",
    val date: String? = "",
    val name: String? = "",
    val vol: String? = ""
)