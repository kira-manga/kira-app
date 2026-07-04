package me.manga.yamiapk.sources_repositry.ru.desu.models.info

import kotlinx.serialization.Serializable

@Serializable
data class Author(
    val people_id: Int? = 0,
    val people_name: String? = ""
)