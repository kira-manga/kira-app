// MediocretoonsHome.kt
package me.manga.yamiapk.sources_repositry.pt.mediocretoons.models

import kotlinx.serialization.Serializable

@Serializable
data class MediocretoonsHome(
    val data: List<MangaData?>?,
    val pagination: Pagination?
)

@Serializable
data class MangaData(
    val id: Int?,
    val nome: String?,
    val imagem: String?,
    val formato: Formato?,
    val criada_em: String?,
    val tags: List<Tag?>?,
    val status: Status?,
    val total_capitulos: Int?,
    val total_lidos: Int?,
    val ultimo_lido: UltimoLido?,
    val leitura: Leitura?,
    val total_usuarios_lendo: Int?,
    val total_usuarios_leram: Int?,
    val total_usuarios_lerao: Int?,
    val links: List<String>?,
    val atualizacoes: Atualizacoes?,
    val agente: Agente?,
    val capitulos_importados: Int?,
    val capitulos_nao_importados: Int?,
    val ultimo_capitulo: UltimoCapitulo?
)

@Serializable
data class Formato(
    val id: Int?,
    val nome: String?
)

@Serializable
data class Tag(
    val id: Int?,
    val nome: String?
)

@Serializable
data class Status(
    val id: Int?, // Made nullable
    val nome: String? // Made nullable
)

@Serializable
data class UltimoLido(
    val capitulo_id: Int?,
    val capitulo_num: String?,
    val prox_capitulo: Int?, // Made nullable
    val prox_capitulo_num: String?, // Made nullable
    val lido_em: String?
)

@Serializable
data class Leitura(
    val id: Int?,
    val status: Status?
)

@Serializable
data class Agente(
    val id: Int?,
    val nome: String?
)

@Serializable
data class UltimoCapitulo(
    val cap_id: Int?,
    val obr_id: Int?,
    val cap_nome: String?,
    val cap_desc: String?,
    val cap_num: String?,
    val cap_image: String?,
    val cap_paginas: String?,
    val cap_importar: Int?,
    val cap_tentativas: Int?,
    val cap_lancado_em: String?,
    val cap_criado_em: String?
)

@Serializable
data class Pagination(
    val currentPage: Int?,
    val totalPages: Int?,
    val totalItems: Int?,
    val itemsPerPage: Int?,
    val hasNextPage: Boolean?,
    val hasPreviousPage: Boolean?
)

@Serializable
data class Atualizacoes(
    val dia_semana: String?,
    val frequencia: String?,
    val status: String?
)


@Serializable
data class Obra(
    val id: Int?,
    val nome: String?,
    val capitulos: List<CapituloInfo?>?
)

@Serializable
data class CapituloInfo(
    val cap_id: Int?,
    val obr_id: Int?,
    val cap_nome: String?,
    val cap_desc: String?,
    val cap_num: String?,
    val cap_image: String?,
    val cap_paginas: String?,
    val cap_importar: Int?,
    val cap_tentativas: Int?,
    val cap_lancado_em: String?,
    val cap_criado_em: String?
)