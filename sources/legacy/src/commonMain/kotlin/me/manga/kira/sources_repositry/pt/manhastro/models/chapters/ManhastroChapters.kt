package me.manga.kira.sources_repositry.pt.manhastro.models.chapters

import kotlinx.serialization.Serializable

@Serializable
data class ManhastroChaptersResponse(
    val success: Boolean = false,
    val data: List<Capitulo> = emptyList()
)