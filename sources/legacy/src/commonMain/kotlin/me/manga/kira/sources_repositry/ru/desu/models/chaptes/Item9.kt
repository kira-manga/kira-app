package me.manga.kira.sources_repositry.ru.desu.models.chaptes
import kotlinx.serialization.Serializable

@Serializable
data class Item9(
    val date: Int? = 0,
    val height: Int? = 0,
    val id: Int? = 0,
    val img: String? = "",
    val page: Int? = 0,
    val width: Int? = 0
)