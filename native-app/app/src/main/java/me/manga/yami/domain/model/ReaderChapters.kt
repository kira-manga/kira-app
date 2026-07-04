package me.manga.yamiapk.domain.model

import android.os.Parcelable
import kotlinx.parcelize.Parcelize
import kotlinx.serialization.Serializable


@Parcelize
@Serializable
data class ReaderChapters(

    val api: String,
    val language: String,
    val chapterNumber: String,   // Chapter number
    val chapterName: String,   // Chapter number
    val isDownloaded: Boolean = false, // Whether the chapter is downloaded
    val url: String,      // URL to read the chapter
    val isBookmarked: Boolean = false,
    val chapterId: Long = 0,
    val mangaId: Long = 0,
    val mangaName: String ,
    val localImagePaths: List<String> = emptyList() // Store local paths of downloaded images
    ) : Parcelable
