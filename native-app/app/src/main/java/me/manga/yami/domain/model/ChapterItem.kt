package me.manga.yamiapk.domain.model

import android.os.Parcelable
import kotlinx.parcelize.Parcelize
import java.time.LocalDate

@Parcelize
data class ChapterItem(
    val number: String, // Chapter number
    val name: String = "", // Chapter name
    val url: String,      // URL to read the chapter
    val date: LocalDate? = LocalDate.now(),
    val isDownloaded: Boolean = false, // Whether the chapter is downloaded
    val isBookmarked: Boolean = false, // Whether the chapter is downloaded
    val chaptersImages: List<ChapterImage> = listOf(),
    ) : Parcelable

