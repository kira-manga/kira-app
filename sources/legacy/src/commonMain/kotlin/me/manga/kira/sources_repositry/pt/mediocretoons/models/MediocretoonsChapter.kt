package me.manga.kira.sources_repositry.pt.mediocretoons.models


import kotlinx.serialization.Serializable

@Serializable
data class MediocretoonsChapter(
    val id: Int?,
    val nome: String?,
    val descricao: String?,
    val numero: String?,
    val imagem: String?,
    val paginas: List<Pagina>?,
    val lancado_em: String?,
    val criado_em: String?,
    val total_comentarios: Int?,
    val links: List<String>?,
    val obra: Obra?,
    val prox_cap: Int?,
    val cap_anterior: Int?,
    val lido: Boolean?
)

@Serializable
data class Pagina(
    val src: String?
)


