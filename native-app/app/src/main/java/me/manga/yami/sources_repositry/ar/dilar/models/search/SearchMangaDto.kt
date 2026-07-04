package me.manga.yamiapk.sources_repositry.ar.dilar.models.search

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import me.manga.yamiapk.sources_repositry.ar.dilar.models.home.Category

@Serializable
data class  SearchMangaDto(
    val mangas: List<BrowseManga>,
)
@Serializable
data class BrowseManga(
     val id: Int,
     val title: String,
     val cover: String? = null,
     val categories: List<Category?>? = listOf(),

     @SerialName("is_novel") val isNovel: Boolean,
)