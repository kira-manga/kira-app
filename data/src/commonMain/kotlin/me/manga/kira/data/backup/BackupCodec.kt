@file:OptIn(ExperimentalTime::class)

package me.manga.kira.data.backup

import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.json.Json
import me.manga.kira.data.backup.model.BackupChapter
import me.manga.kira.data.backup.model.BackupHistoryItem
import me.manga.kira.data.backup.model.BackupManga
import me.manga.kira.data.local.entity.HistoryItemD
import me.manga.kira.data.local.entity.SavedChapterEntity
import me.manga.kira.data.local.entity.SavedMangaEntity
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/** Format version this build writes — and the highest it will accept on import. */
internal const val BACKUP_FORMAT_VERSION = 1

/** Name of the archive entry holding the [me.manga.kira.data.backup.model.BackupFile] JSON. */
internal const val BACKUP_JSON_ENTRY = "backup.json"

/** MangaDatabase version at authoring time — provenance only, import never gates on it. */
internal const val BACKUP_DB_VERSION = 13

/** Lenient on read (additive forward-compat), explicit on write (defaults serialized). */
internal val backupJson =
    Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

// Entity <-> DTO mappers. Dates travel as epoch numbers so the wire format needs no
// kotlinx-datetime serializers; LocalDateTime <-> epoch-ms uses the device timezone (display may
// shift across devices, but the newer-wins merge comparisons happen in the epoch-ms domain).

internal fun SavedMangaEntity.toBackup(chapters: List<BackupChapter>): BackupManga =
    BackupManga(
        api = api,
        language = language,
        url = url,
        imageUrl = imageUrl,
        title = title,
        description = description,
        author = author,
        status = status,
        rating = rating,
        genres = genres,
        savedTimestamp = savedTimestamp,
        lastOpenTimestamp = lastOpenTimestamp,
        isLiked = isLiked,
        isWatchingNow = isWatchingNow,
        chapters = chapters,
    )

internal fun BackupManga.toEntity(): SavedMangaEntity =
    SavedMangaEntity(
        id = 0,
        api = api,
        language = language,
        url = url,
        imageUrl = imageUrl,
        title = title,
        description = description,
        author = author,
        status = status,
        rating = rating,
        genres = genres,
        savedTimestamp = savedTimestamp,
        lastOpenTimestamp = lastOpenTimestamp,
        isLiked = isLiked,
        isWatchingNow = isWatchingNow,
    )

internal fun SavedChapterEntity.toBackup(
    resumePage: Int?,
    downloadEntry: String?,
): BackupChapter =
    BackupChapter(
        name = name,
        number = number,
        url = url,
        dateEpochDay = date?.toEpochDays()?.toLong(),
        isRead = isRead,
        isBookmarked = isBookmarked,
        lastReadDate = lastReadDate,
        resumePage = resumePage,
        downloadEntry = downloadEntry,
    )

/**
 * Incoming chapter row for the merge-import. `mangaId` is a placeholder the DAO overwrites with
 * the resolved local id; the device-local columns start at their pristine defaults — a restored
 * download flips `isDownloaded`/`localImagePaths` separately, only after its CBZ is in place.
 */
internal fun BackupChapter.toEntity(): SavedChapterEntity =
    SavedChapterEntity(
        id = 0,
        mangaId = 0,
        name = name,
        number = number,
        url = url,
        date = dateEpochDay?.let { LocalDate.fromEpochDays(it.toInt()) },
        isDownloaded = false,
        isBookmarked = isBookmarked,
        isRead = isRead,
        isNew = false,
        lastReadPage = 0,
        lastReadDate = lastReadDate,
        localImagePaths = emptyList(),
        fetchedAt = 0,
    )

internal fun HistoryItemD.toBackup(): BackupHistoryItem =
    BackupHistoryItem(
        api = api,
        language = language,
        mangaUrl = mangaUrl,
        mangaTitle = mangaTitle,
        mangaImageUrl = mangaImageUrl,
        chapterUrl = chapterUrl,
        chapterTitle = chapterTitle,
        lastReadDateEpochMs = lastReadDate.toInstant(TimeZone.currentSystemDefault()).toEpochMilliseconds(),
        lastReadPage = lastReadPage,
        totalPages = totalPages,
    )

internal fun BackupHistoryItem.toEntity(resolvedMangaId: Long?): HistoryItemD =
    HistoryItemD(
        id = 0,
        api = api,
        language = language,
        mangaId = resolvedMangaId ?: 0,
        mangaUrl = mangaUrl,
        mangaTitle = mangaTitle,
        mangaImageUrl = mangaImageUrl,
        chapterUrl = chapterUrl,
        chapterTitle = chapterTitle,
        isDownloaded = false,
        localImagePaths = emptyList(),
        lastReadDate =
            Instant
                .fromEpochMilliseconds(lastReadDateEpochMs)
                .toLocalDateTime(TimeZone.currentSystemDefault()),
        lastReadPage = lastReadPage,
        totalPages = totalPages,
    )
