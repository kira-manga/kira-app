package me.manga.kira.sources_repositry.ru.desu.models.info

import kotlinx.serialization.Serializable


@Serializable
data class Item0(
    val ch: Double?  = 0.0,
    val check: Int?  = 0,
    val date: Int?   = 0,
    val id: Int?     = 0,
    val title: String? = "",
    val vol: Int?    = 0
)