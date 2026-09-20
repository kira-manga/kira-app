@file:OptIn(kotlin.time.ExperimentalTime::class)

package me.manga.kira.data.backup

import kotlinx.datetime.DateTimeArithmeticException
import kotlinx.serialization.SerializationException
import kotlinx.serialization.decodeFromString
import me.manga.kira.data.backup.model.BackupChapter
import me.manga.kira.data.backup.model.BackupFile
import me.manga.kira.data.backup.model.BackupManga
import me.manga.kira.data.local.entity.HistoryItemD
import me.manga.kira.platform.backup.BackupByteBudget
import me.manga.kira.platform.backup.BackupImportPolicy
import me.manga.kira.platform.backup.BackupZipEntry
import me.manga.kira.platform.backup.InvalidBackupArchive

internal data class AdmittedBackupManifest(
    val document: BackupFile,
    val references: Set<String>,
    val history: List<HistoryItemD>,
)

internal class BackupFormatTooNew : IllegalArgumentException("Unsupported backup format version")

internal fun admitBackupManifest(
    text: String,
    entries: List<BackupZipEntry>,
    policy: BackupImportPolicy,
    checkpoint: () -> Unit,
): AdmittedBackupManifest {
    val document = decodeBackupManifest(text)
    if (document.formatVersion > BACKUP_FORMAT_VERSION) throw BackupFormatTooNew()
    requireBackup(document.formatVersion == BACKUP_FORMAT_VERSION)
    val records = BackupByteBudget(policy.records.maxChaptersAndHistory.toLong())
    val references = validateMangas(document, policy, records, checkpoint)
    requireBackup(document.includesDownloads || references.isEmpty())
    val expected = references + BACKUP_JSON_ENTRY
    requireBackup(entries.filterNot { it.isDirectory }.map { it.name }.toSet() == expected)
    val history = validateHistory(document, records, checkpoint)
    checkpoint()
    return AdmittedBackupManifest(document, references.toSet(), history)
}

private fun decodeBackupManifest(text: String): BackupFile =
    try {
        backupJson.decodeFromString<BackupFile>(text)
    } catch (failure: SerializationException) {
        throw InvalidBackupArchive(failure)
    } catch (failure: IllegalArgumentException) {
        throw InvalidBackupArchive(failure)
    } catch (failure: DateTimeArithmeticException) {
        throw InvalidBackupArchive(failure)
    }

private fun validateMangas(
    document: BackupFile,
    policy: BackupImportPolicy,
    records: BackupByteBudget,
    checkpoint: () -> Unit,
): Set<String> {
    BackupByteBudget(policy.records.maxMangas.toLong()).consume(document.mangas.size.toLong())
    val urls = mutableSetOf<String>()
    val identities = mutableSetOf<Pair<String, String>>()
    val references = linkedSetOf<String>()
    for (manga in document.mangas) {
        checkpoint()
        requireIdentity(manga.api)
        requireIdentity(manga.title)
        requireIdentity(manga.url)
        // Titles are display metadata; different same-title works remain distinct portable owners.
        requireBackup(urls.add(manga.url) && identities.add(manga.api to manga.url))
        records.consume(manga.chapters.size.toLong())
        validateChapters(manga, references, checkpoint)
    }
    return references
}

private fun validateChapters(
    manga: BackupManga,
    references: MutableSet<String>,
    checkpoint: () -> Unit,
) {
    val urls = mutableSetOf<String>()
    for (chapter in manga.chapters) {
        checkpoint()
        requireIdentity(chapter.url)
        requireBackup(urls.add(chapter.url))
        requireBackup(chapter.resumePage == null || chapter.resumePage >= 0)
        chapter.downloadEntry?.let { entry ->
            requireBackup(DOWNLOAD_ENTRY.matches(entry) && references.add(entry))
        }
        validateChapterDate(chapter)
    }
}

private fun validateChapterDate(chapter: BackupChapter) {
    chapter.dateEpochDay?.let { requireBackup(it in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) }
    try {
        // Exercise the mapper's date conversion before ANY merge can occur. No narrowing overflow.
        chapter.toEntity()
    } catch (failure: IllegalArgumentException) {
        throw InvalidBackupArchive(failure)
    } catch (failure: DateTimeArithmeticException) {
        throw InvalidBackupArchive(failure)
    }
}

private fun validateHistory(
    document: BackupFile,
    records: BackupByteBudget,
    checkpoint: () -> Unit,
): List<HistoryItemD> {
    records.consume(document.history.size.toLong())
    val urls = mutableSetOf<String>()
    return document.history.map { item ->
        checkpoint()
        requireIdentity(item.mangaUrl)
        requireBackup(urls.add(item.mangaUrl) && item.lastReadPage >= 0 && item.totalPages >= 0)
        try {
            // Keep this converted timestamp in the plan: a later timezone change cannot invalidate
            // a previously admitted Instant/LocalDateTime after the first manga has been merged.
            item.toEntity(resolvedMangaId = null)
        } catch (failure: IllegalArgumentException) {
            throw InvalidBackupArchive(failure)
        } catch (failure: DateTimeArithmeticException) {
            throw InvalidBackupArchive(failure)
        }
    }
}

private fun requireIdentity(value: String) {
    requireBackup(value.isNotBlank() && '\u0000' !in value)
}

private fun requireBackup(valid: Boolean) {
    if (!valid) throw InvalidBackupArchive()
}

private val DOWNLOAD_ENTRY = Regex("downloads/(0|[1-9][0-9]*)\\.cbz")
