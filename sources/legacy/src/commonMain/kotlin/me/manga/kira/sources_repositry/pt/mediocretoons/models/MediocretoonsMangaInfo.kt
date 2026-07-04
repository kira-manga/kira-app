package me.manga.kira.sources_repositry.pt.mediocretoons.models

import kotlinx.serialization.Serializable

@Serializable
data class MediocretoonsMangaInfo(
    val id: Int?,
    val nome: String?,
    val descricao: String?,
    val imagem: String?,
    val formato: Formato?,
    val criada_em: String?,
    val tags: List<Tag?>?,
    val status: Status?,
    val leitura: Leitura?,
    val total_capitulos: Int?,
    val total_lidos: Int?,
    val ultimo_lido: UltimoLido?,
    val capitulos: List<Capitulo?>?,
    val links: List<String>?,
    val atualizacoes: Atualizacoes?,
    val agente: Agente?,
    val total_usuarios_lendo: Int?,
    val total_usuarios_leram: Int?,
    val total_usuarios_lerao: Int?
)

//@Serializable
//data class Formato(
//    val id: Int?,
//    val nome: String?
//)

//@Serializable
//data class Tag(
//    val id: Int?,
//    val nome: String?
//)
//
//@Serializable
//data class Status(
//    val id: Int?,
//    val nome: String?
//)
//
//@Serializable
//data class Leitura(
//    val id: Int?,
//    val status: Status?
//)
//
//@Serializable
//data class UltimoLido(
//    val capitulo_id: Int?,
//    val prox_capitulo: Int?,
//    val lido_em: String?
//)

@Serializable
data class Capitulo(
    val id: Int?,
    val nome: String?,
    val tem_paginas: Boolean?,
    val imagem: String?,
    val descricao: String?,
    val lancado_em: String?,
    val criado_em: String?,
    val numero: String?,
    val lido: Boolean?,
    val totallinks: Int?
)
