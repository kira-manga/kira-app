package me.manga.kira.sources_repositry.pt.manhastro.models.chapters

import kotlinx.serialization.Serializable

@Serializable
data class Capitulo(
    val capitulo_id: Int = 0,
    val capitulo_nome: String = "",
    val capitulo_data: String = ""
)
