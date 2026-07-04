package me.manga.kira.sources_repositry.ar.promanga.models.info
import kotlinx.serialization.Serializable
@Serializable
data class Map(
    val dim: List<Int>,
    val mode: String,
    val order: List<Int>,
    val pieces: List<String>
)