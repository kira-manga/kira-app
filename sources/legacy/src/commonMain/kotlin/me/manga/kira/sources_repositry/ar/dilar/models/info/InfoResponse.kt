package me.manga.kira.sources_repositry.ar.dilar.models.info

import kotlinx.serialization.Serializable


@Serializable
data class InfoResponse(
    val mangaData: MangaData? = MangaData(),
    val mangaLibrary: MangaLibrary? = MangaLibrary(),
)