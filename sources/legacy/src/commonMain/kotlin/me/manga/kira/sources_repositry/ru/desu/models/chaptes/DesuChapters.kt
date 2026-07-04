package me.manga.kira.sources_repositry.ru.desu.models.chaptes

import kotlinx.serialization.Serializable

@Serializable
data class DesuChapters(
    val response: Response? = Response()
)