package me.manga.yamiapk.domain.model

import androidx.annotation.Keep

@Keep
data class SearchItems(
    val title: String,
    val url: String,
    val type: String
)
