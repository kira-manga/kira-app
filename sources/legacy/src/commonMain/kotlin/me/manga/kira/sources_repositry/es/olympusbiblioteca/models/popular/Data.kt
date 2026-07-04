package me.manga.kira.sources_repositry.es.olympusbiblioteca.models.popular

import kotlinx.serialization.Serializable


@Serializable
data class Data(
    val slider: List<Slider?>? = listOf(),
    val popular_comics: String? = "" // This is a JSON string that needs separate parsing

)