package me.manga.yamiapk.domain.model

import me.manga.yamiapk.data.local.entity.SavedMangaEntity

data class MangaDisplayItem(
    val manga: SavedMangaEntity,
    val totalChapters: Int,
    val readCount: Int,
    val downloadedCount: Int,
    val bookmarkedCount: Int,

)
