package me.manga.yamiapk.sources_repositry.ar.swatmanga.models.home

import kotlinx.serialization.Serializable


@Serializable
data class Status(
    val id: Int? = 0,
    val name: String? = ""
)