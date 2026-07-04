package me.manga.yamiapk.domain.model


import android.os.Parcelable
import kotlinx.parcelize.Parcelize

    @Parcelize
    data class MangaInfo(
        val api: String,
        val language: String,
        val url: String,
        val title: String,
        val imageUrl: String,
        val rating: String,
        val ratingCount: String,
        val description: String,
        val otherNames: String,
        val author: String,
        val artist: String,
        val genres: List<String>,
        val tags: List<String>,
        val yearOfProduction: String,
        val status: String,
        val favoritesCount: String,
        val chapters: MutableList<ChapterItem>
    ) : Parcelable

