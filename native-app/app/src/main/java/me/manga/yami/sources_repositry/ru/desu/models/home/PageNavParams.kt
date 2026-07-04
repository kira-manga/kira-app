package me.manga.yamiapk.sources_repositry.ru.desu.models.home

import kotlinx.serialization.Serializable

@Serializable
data class PageNavParams(
    val count: Int? = 0,
    val limit: Int? = 0,
    val order_by: String? = "",
    val page: Int? = 0
)