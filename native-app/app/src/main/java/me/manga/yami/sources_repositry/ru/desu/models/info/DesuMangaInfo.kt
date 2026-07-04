package me.manga.yamiapk.sources_repositry.ru.desu.models.info

import kotlinx.serialization.Serializable

@Serializable
data class DesuMangaInfo(
    val response: Response? = Response()
)