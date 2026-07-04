package me.manga.yamiapk.domain.model

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

@Parcelize
data class MangaItem(
    val api: String,
    val language: String,
    val title: String,
    val url: String,
    val imageUrl: String,
    val rating: Int?,
    val chapters: List<ChapterItem>?,
    val genres: List<String>
) : Parcelable

