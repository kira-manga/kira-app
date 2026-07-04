package me.manga.yamiapk.domain.model

import androidx.annotation.Keep

@Keep
data class MangaSearchResponse(
    val success: Boolean       = false,
    val data:    List<SearchItems> = emptyList()
)