package me.manga.yamiapk.sources_repositry.ru.desu.models.chaptes
import kotlinx.serialization.Serializable

@Serializable
data class Pages(
    val list: List<Item9?>? = listOf()
)