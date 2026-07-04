package me.manga.yamiapk.sources_repositry.ar.promanga.models.info
import kotlinx.serialization.Serializable
@Serializable
data class MetadataX(
    val altTitles: List<String?>? = listOf(),
    val descriptions: Descriptions? = Descriptions(),
    val genres: List<String?>? = listOf(),

)