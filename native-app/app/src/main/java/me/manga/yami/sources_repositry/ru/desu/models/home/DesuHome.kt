package me.manga.yamiapk.sources_repositry.ru.desu.models.home

import kotlinx.serialization.Serializable

@Serializable
data class DesuHome(
    val pageNavParams: PageNavParams? = PageNavParams(),
    val response: List<Response?>? = listOf()
)