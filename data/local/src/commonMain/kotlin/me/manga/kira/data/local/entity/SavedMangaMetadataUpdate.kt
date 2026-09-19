package me.manga.kira.data.local.entity

import androidx.room.Embedded
import androidx.room.TypeConverters
import me.manga.kira.data.local.converter.StringListConverter

/**
 * Fetched metadata only. [id] is the retained row selector, never an insertion/replacement ID.
 * No source/work URL, affinity, saved/opened timestamps or child state can be carried by this patch.
 */
@TypeConverters(StringListConverter::class)
data class SavedMangaMetadataUpdate(
    val id: Long,
    @Embedded val content: SavedMangaContentMetadata,
    @Embedded val classification: SavedMangaClassificationMetadata,
) {
    init {
        require(id > 0)
    }
}

/** Descriptive columns returned by a same-work metadata fetch. */
data class SavedMangaContentMetadata(
    val title: String,
    val description: String,
    val author: String,
    val imageUrl: String,
)

/** Classification columns; genres retain the existing Room string-list encoding. */
@TypeConverters(StringListConverter::class)
data class SavedMangaClassificationMetadata(
    val language: String,
    val status: String,
    val rating: String?,
    val genres: List<String>,
)
