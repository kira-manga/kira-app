package me.manga.kira.sources_repositry.ar.promanga.models.info
import kotlinx.serialization.Serializable
@Serializable
data class Card(
    val desktop: String? = "",
    val mobile: String? = ""
)