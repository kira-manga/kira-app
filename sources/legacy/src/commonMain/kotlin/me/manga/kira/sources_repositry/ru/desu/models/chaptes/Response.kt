package me.manga.kira.sources_repositry.ru.desu.models.chaptes
import kotlinx.serialization.Serializable

@Serializable
data class Response(
    val name :String="",

    val pages: Pages? = Pages(),

)