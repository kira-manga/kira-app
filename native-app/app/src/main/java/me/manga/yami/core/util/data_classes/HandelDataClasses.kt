package me.manga.yamiapk.core.util.data_classes

import android.util.Log
import me.manga.yamiapk.data.local.entity.ChapterDownloadEntity
import me.manga.yamiapk.data.local.entity.ChapterNotification
import me.manga.yamiapk.data.local.entity.HistoryItemD
import me.manga.yamiapk.data.local.entity.SavedChapterEntity
import me.manga.yamiapk.data.local.entity.SavedMangaEntity
import me.manga.yamiapk.domain.model.ChapterImage
import me.manga.yamiapk.domain.model.ChapterItem
import me.manga.yamiapk.domain.model.MangaInfo
import me.manga.yamiapk.domain.model.MangaItem
import me.manga.yamiapk.domain.model.PopularManga
import me.manga.yamiapk.domain.model.ReaderChapters
import me.manga.yamiapk.presentation.features.download.data.DownloadingState
import java.time.LocalDate
import java.time.LocalDateTime

object HandelDataClasses {

    fun emptyMangaItem(): MangaItem {
        return MangaItem(
            title = "empty Title",
            url = "empty url",
            imageUrl = "empty image url",
            rating = 0,
            chapters = listOf(),
            genres = listOf(),
            api = "empty api",
            language = "empty language"
        )
    }

    fun emptyMangaInfo(): MangaInfo {
        return MangaInfo(
            title = "empty Title",
            url = "empty url",
            imageUrl = "empty image url",
            genres = listOf(),
            api = "empty api",
            ratingCount = "empty ratingCount",
            description = "empty description",
            otherNames = "empty otherNames",
            author = "empty author",
            artist = "empty artist",
            tags = listOf(),
            yearOfProduction = "empty yearOfProduction",
            status = "empty status",
            favoritesCount = "empty favoritesCount",
            rating = "empty rating",
            chapters = mutableListOf(),
            language = "empty language"
        )
    }
    fun emptyChapterItem(): ChapterItem {
        return ChapterItem(
            url = "empty url",
            number = "empty url",
            date = LocalDate.now(),
            isDownloaded = false,
            isBookmarked = false,
            chaptersImages = listOf(),
        )
    }

    fun emptySavedMangaEntity(): SavedMangaEntity {
        return SavedMangaEntity(
            api = "",
            language = "",
            url = "",
            imageUrl = "",
            title = "",
            description = "",
            status = "",
            rating = "",
            genres = listOf(),
        )
    }
    fun MangaItem.toMangaInfo(chapter: ChapterItem): MangaInfo {
        return MangaInfo(
            api = this.api,
            url = this.url,
            title = this.title,
            imageUrl = this.imageUrl,
            rating = "empty url",
            ratingCount = "empty url",
            description = "empty url",
            otherNames = "empty url",
            author = "empty url",
            artist = "empty url",
            genres = listOf(),
            tags = listOf(),
            yearOfProduction = "empty url",
            status = "empty url",
            favoritesCount = "empty url",
            chapters = mutableListOf(chapter),
            language = this.language
        )

    }



    fun SavedChapterEntity.toReaderChapters(mangaName:String): ReaderChapters {
        return ReaderChapters(
            chapterNumber = this.number,
            chapterName = this.name,
            isDownloaded = this.isDownloaded,
            url = this.url,
            isBookmarked = this.isBookmarked,
            chapterId = this.id,
            mangaId = this.mangaId,
            localImagePaths = this.localImagePaths,
            mangaName = mangaName,
            api = "",
            language = ""
        )
    }


    fun ChapterItem.toReaderChapters(mangaName:String): ReaderChapters {
        return ReaderChapters(
            chapterNumber = this.number,
            chapterName = this.name,
            isDownloaded = this.isDownloaded,
            url = this.url,
            isBookmarked = this.isBookmarked,
            mangaName = mangaName,
            api = "",
            language = "",

            )
    }

    // now add these two:
    inline fun <T> List<T>.mapToReaderChapters(crossinline transform: (T) -> ReaderChapters): List<ReaderChapters> =
        map { transform(it) }

    inline fun <T> List<T>.mapToReaderChaptersString(crossinline transform: (T) -> String): List<String> =
        map { transform(it) }



    inline fun <T> List<String>.mapFromJson(
        crossinline transform: (String) -> T
    ): List<T> = map { transform(it) }
    /**
     * Extension functions to map between MangaItem (network/serialization model)
     * and SavedMangaEntity (Room persistence model).
     */

    /**
     * Convert a MangaItem into a SavedMangaEntity for storing in Room.
     * @param id optional primary key (default 0, so Room will auto-generate).
     * @param savedTimestamp optional timestamp (default now).
     */
    fun MangaItem.toSavedEntity(
        id: Long = 0L,
        savedTimestamp: Long = System.currentTimeMillis()
    ): SavedMangaEntity = SavedMangaEntity(
        id = id,
        title = this.title,
        url = this.url,
        imageUrl = this.imageUrl,
        rating = this.rating.toString(),
        genres = this.genres,

        savedTimestamp = savedTimestamp,
        api = this.api,
        language = this.language,
        description = this.title,
        status = this.rating.toString()
    )

    /**
     * Convert a SavedMangaEntity back into a MangaItem.
     * Note: chapters are not stored in the entity; you may pass them in if needed.
     * @param chapters optional list of chapters (default null).
     */
    fun SavedMangaEntity.toMangaItem(
        chapters: List<ChapterItem>? = null
    ): MangaItem = MangaItem(
        title = this.title,
        url = this.url,
        imageUrl = this.imageUrl,
        rating = this.rating?.toIntOrNull(),
        chapters = chapters,
        genres = this.genres,
        api = this.api,
        language = this.language
    )

    /**
     * Convert a SavedMangaEntity back into a MangaItem.
     * @param chapters optional list of chapters (default empty).
     */


// ---------------- Chapter Mapping ----------------

    /**
     * Convert a ChapterItem into a SavedChapterEntity for storing in Room.
     * @param mangaId the parent manga's primary key in SavedMangaEntity.
     * @param id optional primary key (default 0, so Room will auto-generate).
     */
    fun ChapterItem.toSavedEntity(
        mangaId: Long,
        id: Long = 0L
    ): SavedChapterEntity = SavedChapterEntity(
        id = id,
        mangaId = mangaId,
        number = this.number,
        url = this.url,
        date = date ?: LocalDate.now(),
        isDownloaded = isDownloaded,
        isBookmarked = isBookmarked,
        name = this.name,
    )

    /**
     * Convert a SavedChapterEntity back into a ChapterItem.
     * Note: chapterImages are not stored; default empty.
     */
    fun SavedChapterEntity.toChapterItem(): ChapterItem = ChapterItem(
        number = number,
        url = url,
        date = LocalDate.now(),
        isDownloaded = isDownloaded,
        isBookmarked = isBookmarked,
        chaptersImages = this.localImagePaths.mapIndexed { index, path ->
            ChapterImage(image = path, index = index)
        }
    )



// ---------------- List Mapping Helpers ----------------

    /**
     * Convert a list of ChapterItems into SavedChapterEntities under the given mangaId.
     */
    fun List<ChapterItem>.toSavedEntities(mangaId: Long): List<SavedChapterEntity> =
        map { it.toSavedEntity(mangaId) }
//    fun List<ChapterItem>.toSavedEntities(mangaId: Long): List<SavedChapterEntity> =
//        map { it.toSavedEntity(mangaId) }

    /**
     * Convert a list of SavedChapterEntities back into ChapterItems.
     */
    fun List<SavedChapterEntity>.toChapterItems(): List<ChapterItem> =
        map { it.toChapterItem() }


//    SavedMangaEntity to  MangaInfo

    fun SavedMangaEntity.toMangaInfo(chapters :List<ChapterItem>): MangaInfo {
        return MangaInfo(
            api = this.api, // Assuming this is local saved manga
            url = this.url,
            title = this.title,
            imageUrl = this.imageUrl,
            rating = this.rating?.toString() ?: "0",
            ratingCount = "0", // Placeholder
            description = "", // Placeholder
            otherNames = "", // Placeholder
            author = "", // Placeholder
            artist = "", // Placeholder
            genres = this.genres,
            tags = emptyList(), // Placeholder
            yearOfProduction = "", // Placeholder
            status = "", // Placeholder
            favoritesCount = "0", // Placeholder
            chapters = chapters.toMutableList(),
            language = this.language // Placeholder
        )
    }

    fun MangaInfo.toMangaItem(): MangaItem = MangaItem(
        api = this.api,
        language = this.language,
        title = this.title,
        url = this.url,
        imageUrl = this.imageUrl,
        // parse the String rating to an Int?, or null if it fails
        rating = this.rating.toIntOrNull(),
        // use the list only if non-empty
        chapters = listOf(),
        genres = this.genres
    )

    fun HistoryItemD.toMangaItem(): MangaItem = MangaItem(
        api = this.api,
        language = this.language,
        title = this.mangaTitle,
        url = this.mangaUrl,
        imageUrl = this.mangaImageUrl,
        // parse the String rating to an Int?, or null if it fails
        rating = 0,
        // use the list only if non-empty
        chapters = listOf(),
        genres = listOf()
    )
    fun HistoryItemD.toChapterItem(): ChapterItem = ChapterItem(
        url = this.chapterUrl,
        number = this.chapterTitle,

        )
    fun HistoryItemD.toMangaInfo(chapter: ChapterItem): MangaInfo = MangaInfo(
        api = this.api,
        language = this.language,
        url = this.mangaUrl,
        title = this.mangaTitle,
        imageUrl = this.mangaImageUrl,
        rating = "0",
        ratingCount = "0",
        description = "",
        otherNames = "TODO()",
        author = "TODO()",
        artist = "TODO()",
        genres = listOf(),
        tags = listOf(),
        yearOfProduction = "",
        status = "TODO()",
        favoritesCount = "",
        chapters = mutableListOf(chapter)
    )
    fun MangaInfo.toSavedEntity(): SavedMangaEntity {
        return SavedMangaEntity(
            id = 0,
            api = this.api,
            language = this.language,
            url = this.url,
            imageUrl = this.imageUrl,
            title = this.title,
            description = this.description,
            status = this.status,
            rating = this.rating,
            genres = this.genres,
            savedTimestamp = System.currentTimeMillis()
        )
    }


    fun ReaderChapters.toHistoryItemD(
        mangaUrl: String,
        mangaImageUrl: String
    ): HistoryItemD = HistoryItemD(
        mangaTitle = this.mangaName,
        mangaUrl = mangaUrl,
        mangaImageUrl = mangaImageUrl,
        chapterTitle = this.chapterNumber,
        chapterUrl = this.url,
        api = this.api,
        language = this.language,
        isDownloaded = this.isDownloaded,
        lastReadDate = LocalDateTime.now(),
        localImagePaths = this.localImagePaths,
        mangaId = this.mangaId
    )

    fun ChapterNotification.toSavedChapterEntity(): SavedChapterEntity {
        return SavedChapterEntity(
            // Let Room generate a fresh PK for the saved chapter
            id = this.chapterId,
            // carry over the manga ID
            mangaId = this.mangaId,
            // choose what you want as the "name" – here we use the manga title,
            // but you could also do "$mangaTitle Chapter $chapterNumber" or just chapterNumber
            name = this.chapterNumber,
            // the chapter number string
            number = this.chapterNumber,
            // URL to the chapter
            url = this.chapterUrl,
            // we'll default the saved date to the notification date
            date = this.notificationDate,
            // if the notification had the images already downloaded, carry that over
            isDownloaded = this.isDownloaded,
            // new saves aren’t bookmarked by default
            isBookmarked = false,
            // if the user tapped the notification to read, mark it read here too
            isRead = this.isRead,
            // no pages read yet
            lastReadPage = 0,
            // no last-read timestamp yet (you could also map notificationDate to epoch millis)
            lastReadDate = 0L,
            // any local image paths from the notification
            localImagePaths = this.localImagePaths
        )
    }











    fun ChapterDownloadEntity.toChapterEntity(
    ): SavedChapterEntity = SavedChapterEntity(
        id = this.chapterId,
        mangaId = this.mangaId,
        name = this.number,
        number = this.number,
        url = this.url,
        isDownloaded = false,
        isBookmarked = false,
    )

    val emptyMangaInfo = MangaInfo(
        api              = "",
        language         = "",
        url              = "",
        title            = "",
        imageUrl         = "",
        rating           = "",
        ratingCount      = "",
        description      = "",
        otherNames       = "",
        author           = "",
        artist           = "",
        genres           = emptyList(),
        tags             = emptyList(),
        yearOfProduction = "",
        status           = "",
        favoritesCount   = "",
        chapters         = mutableListOf()
    )


    fun SavedChapterEntity.toChapterDownloadEntity(
        apiName: String,
        title: String,
        initialState: DownloadingState = DownloadingState.QUEUED
    ): ChapterDownloadEntity = ChapterDownloadEntity(
        // id = 0 so that Room will auto-generate
        chapterId = this.id,             // originally a SavedChapterEntity primaryKey
        mangaId = this.mangaId,
        api = apiName,
        mangaTitle = title,
        url = this.url,
        state = initialState,
        progress = 0,
        errorMsg = null,
        number = this.number
    )

    // Bulk conversion
    fun List<SavedChapterEntity>.toChapterDownloadEntities(
        apiName: String,
        title: String,
        initialState: DownloadingState = DownloadingState.QUEUED
    ): List<ChapterDownloadEntity> =
        this.map { it.toChapterDownloadEntity(apiName,title, initialState) }

    fun List<MangaItem>.toPopularMangaList(): List<PopularManga> {

        return this.map { item ->
            PopularManga(
                api = item.api,
                language = item.language,
                title = item.title,
                url = item.url,
                imageUrl = item.imageUrl
            )
        }
    }
}