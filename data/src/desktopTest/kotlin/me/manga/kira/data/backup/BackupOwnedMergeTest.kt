package me.manga.kira.data.backup

import kotlinx.coroutines.test.runTest
import me.manga.kira.data.backup.model.BackupChapter
import me.manga.kira.data.backup.model.BackupFile
import me.manga.kira.data.backup.model.BackupHistoryItem
import me.manga.kira.data.repository.libraryHistory
import me.manga.kira.data.repository.libraryParent
import me.manga.kira.data.repository.librarySavedChapter
import me.manga.kira.data.repository.progress.PROGRESS_CHAPTER
import me.manga.kira.data.repository.progress.PROGRESS_PREVIOUS
import me.manga.kira.data.repository.progress.PROGRESS_WORK
import me.manga.kira.data.repository.progress.ProgressRuntimeFixture
import me.manga.kira.data.repository.progress.ProgressSavedFamily
import me.manga.kira.data.repository.progress.family
import me.manga.kira.data.repository.progress.seedLegacy
import me.manga.kira.domain.model.backup.BackupScope
import me.manga.kira.domain.model.backup.BackupSelection
import me.manga.kira.domain.model.identity.ChapterLocator
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Real owned writer controls, moved from the policy-free DAO suite where appropriate. */
class BackupOwnedMergeTest {
    @Test
    fun backup_with_chapters_1_to_100_over_local_1_to_95_ends_at_1_to_100() = runTest {
        ProgressRuntimeFixture().use { f ->
            val parent = f.seedNumbered(95)
            val result = f.mergeBackup(numberedArchive(100)).single()
            assertEquals(parent.id, result.manga?.id)
            assertFalse(result.mangaWasNew)
            assertEquals(5, result.chaptersAdded)
            assertEquals(95, result.chaptersMerged)
            val rows = f.db.backupDao().getChaptersForManga(parent.id).associateBy { it.number.toInt() }
            assertEquals(100, rows.size)
            for (number in 96..100) {
                val created = rows.getValue(number)
                assertTrue(created.isRead)
                assertEquals(9_000L, created.lastReadDate)
                assertEquals(parent.id, created.mangaId)
            }
            assertTrue(rows.getValue(10).isBookmarked)
            assertTrue(rows.getValue(70).isRead)
            assertEquals(9_000L, rows.getValue(95).lastReadDate)
            assertTrue(rows.getValue(20).isDownloaded)
            assertEquals(listOf("/device/ch20.cbz"), rows.getValue(20).localImagePaths)
            assertEquals(rows.getValue(95), result.chapters[ChapterLocator(PROGRESS_WORK, rows.getValue(95).url)])
        }
    }

    @Test
    fun rerunning_the_same_import_is_idempotent() = runTest {
        ProgressRuntimeFixture().use { f ->
            val parent = f.seedNumbered(95)
            f.mergeBackup(numberedArchive(100))
            val before = f.db.backupDao().getChaptersForManga(parent.id)
            val second = f.mergeBackup(numberedArchive(100)).single()
            assertEquals(0, second.chaptersAdded)
            assertEquals(100, second.chaptersMerged)
            assertEquals(before, f.db.backupDao().getChaptersForManga(parent.id))
        }
    }

    @Test
    fun manga_absent_locally_is_created_with_all_its_chapters() = runTest {
        ProgressRuntimeFixture().use { f ->
            val result = f.mergeBackup(numberedArchive(3)).single()
            assertTrue(result.mangaWasNew)
            assertEquals(3, result.chaptersAdded)
            assertEquals(0, result.chaptersMerged)
            val parent = assertNotNull(result.manga)
            assertEquals(parent, f.db.backupDao().getAllSavedManga().single())
            assertEquals(3, f.db.backupDao().getChaptersForManga(parent.id).size)
        }
    }

    @Test
    fun proved_alias_retains_stored_urls_ids_metadata_and_normalizes_history_chapter() = runTest {
        ProgressRuntimeFixture().use { f ->
            val family = f.family(libraryParent(PROGRESS_PREVIOUS.work.url), PROGRESS_PREVIOUS.chapterUrl)
            val old = libraryHistory(family.work)
            f.db.backupDao().insertHistoryRow(old)
            val incoming = old.toBackup().copy(
                mangaUrl = PROGRESS_WORK.url,
                chapterUrl = PROGRESS_CHAPTER.chapterUrl,
                lastReadDateEpochMs = old.toBackup().lastReadDateEpochMs + 1,
                lastReadPage = 0,
            )
            val result = f.mergeBackup(backupDocument().copy(history = listOf(incoming))).single()
            assertEquals(family.work, result.manga)
            assertEquals(family.chapter.id, result.chapters.getValue(PROGRESS_CHAPTER).id)
            assertEquals(PROGRESS_PREVIOUS.chapterUrl, result.chapters.getValue(PROGRESS_CHAPTER).url)
            val history = f.db.backupDao().getAllHistoryOnce().single()
            assertEquals(old.mangaUrl, history.mangaUrl)
            assertEquals(family.work.id, history.mangaId)
            assertEquals(PROGRESS_PREVIOUS.chapterUrl, history.chapterUrl)
            assertEquals(0, history.lastReadPage)
        }
    }

    @Test
    fun renamed_same_url_and_distinct_same_title_selection_never_use_display_identity() = runTest {
        ProgressRuntimeFixture().use { f ->
            val first = f.parent(libraryParent().copy(title = "Renamed"))
            val other = f.parent(libraryParent("${PROGRESS_WORK.url}-other").copy(title = first.title))
            val result = f.mergeBackup(backupDocument()).single()
            assertEquals(first, result.manga)
            val scope = BackupScope.Mangas(listOf(BackupSelection(PROGRESS_WORK, "Obsolete title")))
            val exported = assertNotNull(f.backupWriter().export(scope) { false })
            assertEquals(listOf(first), exported.mapNotNull { it.manga })
            assertEquals(other, f.db.backupDao().getMangaByUrl(other.url))
        }
    }

    @Test
    fun invalid_missing_and_duplicate_scopes_fail_instead_of_dropping_or_broadening() = runTest {
        ProgressRuntimeFixture().use { f ->
            f.parent()
            val key = BackupSelection(PROGRESS_WORK)
            val missing = key.copy(work = PROGRESS_WORK.copy(url = "${PROGRESS_WORK.url}-missing"))
            val scopes = listOf(
                BackupScope.Invalid,
                BackupScope.Mangas(emptyList()),
                BackupScope.Mangas(listOf(key, key)),
                BackupScope.Mangas(listOf(missing)),
            )
            for (scope in scopes) assertFailsWith<BackupOwnershipException> { f.backupWriter().export(scope) { false } }
            assertTrue(f.db.readerProgressDao().worksForApi(PROGRESS_WORK.api).isEmpty())
        }
    }

    @Test
    fun chapter_lookup_is_mangaId_scoped_so_shared_urls_do_not_cross_mangas() = runTest {
        ProgressRuntimeFixture().use { f ->
            val other = f.parent(libraryParent("${PROGRESS_WORK.url}-other"))
            val kept = f.chapter(librarySavedChapter(other, PROGRESS_CHAPTER.chapterUrl))
            val result = f.mergeBackup(backupDocument()).single()
            assertEquals(1, result.chaptersAdded)
            assertTrue(result.chapters.getValue(PROGRESS_CHAPTER).id != kept.id)
            assertEquals(listOf(kept), f.db.backupDao().getChaptersForManga(other.id))
        }
    }

    @Test
    fun entire_incoming_alias_families_are_checked_before_a_first_group_writes() = runTest {
        val manga = backupDocument().mangas.single()
        val history = BackupHistoryItem(api = PROGRESS_WORK.api, mangaUrl = PROGRESS_WORK.url)
        val aliasChapter = manga.chapters.single().copy(url = PROGRESS_PREVIOUS.chapterUrl)
        val aliasHistory = history.copy(mangaUrl = PROGRESS_PREVIOUS.work.url)
        val invalid = listOf(
            BackupFile(mangas = listOf(manga, manga)),
            BackupFile(mangas = listOf(manga, manga.copy(url = PROGRESS_PREVIOUS.work.url))),
            BackupFile(mangas = listOf(manga, manga.copy(api = "foreign"))),
            BackupFile(mangas = listOf(manga.copy(chapters = manga.chapters + aliasChapter))),
            BackupFile(mangas = listOf(manga.copy(chapters = manga.chapters + manga.chapters))),
            BackupFile(mangas = listOf(manga), history = listOf(history, aliasHistory)),
        )
        for (document in invalid) ProgressRuntimeFixture().use { f ->
            assertFailsWith<BackupOwnershipException> { f.mergeBackup(document) }
            assertTrue(f.db.backupDao().getAllSavedManga().isEmpty())
            assertTrue(f.db.readerLegacyCleanupDao().allReceipts().isEmpty())
        }
    }

    @Test
    fun exact_hits_never_hide_saved_child_native_anchor_or_history_family_conflicts() = runTest {
        for (conflict in Conflict.entries) ProgressRuntimeFixture().use { f ->
            val family = f.family()
            f.seedConflict(family, conflict)
            val captured = f.seedLegacy()
            f.ownership.granted = true
            val parents = f.db.backupDao().getAllSavedManga()
            val children = f.db.backupDao().getChaptersForManga(family.work.id)
            val history = f.db.backupDao().getAllHistoryOnce()
            val anchors = f.db.readerProgressDao().worksForApi(PROGRESS_WORK.api)
            val positions = anchors.associateWith { f.db.readerProgressDao().chaptersForWork(it.workId) }
            assertFailsWith<IllegalStateException>(conflict.name) { f.mergeBackup(backupDocument(page = 0)) }
            assertEquals(parents, f.db.backupDao().getAllSavedManga())
            assertEquals(children, f.db.backupDao().getChaptersForManga(family.work.id))
            assertEquals(history, f.db.backupDao().getAllHistoryOnce())
            assertEquals(anchors, f.db.readerProgressDao().worksForApi(PROGRESS_WORK.api))
            for ((anchor, rows) in positions) {
                assertEquals(rows, f.db.readerProgressDao().chaptersForWork(anchor.workId))
            }
            assertTrue(f.db.readerLegacyCleanupDao().allReceipts().isEmpty())
            assertEquals(captured.payload, f.settings.getStringOrNull(captured.key))
            assertEquals(0, f.settings.removeAttempts)
        }
    }

    @Test
    fun history_absent_inserts_newer_replaces_position_older_keeps_local() = runTest {
        ProgressRuntimeFixture().use { f ->
            val first = BackupHistoryItem(
                api = PROGRESS_WORK.api,
                mangaUrl = PROGRESS_WORK.url,
                chapterUrl = PROGRESS_CHAPTER.chapterUrl,
                lastReadDateEpochMs = 100,
                lastReadPage = 2,
            )
            f.mergeBackup(BackupFile(history = listOf(first)))
            val inserted = f.db.backupDao().getAllHistoryOnce().single()
            val newer = first.copy(lastReadDateEpochMs = 200, lastReadPage = 7, mangaTitle = "Not authority")
            f.mergeBackup(BackupFile(history = listOf(newer)))
            val merged = f.db.backupDao().getAllHistoryOnce().single()
            assertEquals(inserted.id, merged.id)
            assertEquals(0L, merged.mangaId, "safe unsaved history does not fabricate a saved parent")
            assertEquals(inserted.mangaTitle, merged.mangaTitle)
            assertEquals(7, merged.lastReadPage)
            for (date in listOf(100L, 200L)) {
                f.mergeBackup(BackupFile(history = listOf(first.copy(lastReadDateEpochMs = date))))
            }
            assertEquals(merged, f.db.backupDao().getAllHistoryOnce().single())
            assertFailsWith<BackupOwnershipException> { f.mergeBackup(BackupFile(history = listOf(first.copy(api = "")))) }
            assertTrue(f.db.backupDao().getAllSavedManga().isEmpty())
            assertTrue(f.db.readerProgressDao().worksForApi(PROGRESS_WORK.api).isEmpty())
        }
    }
}

private fun numberedArchive(count: Int): BackupFile {
    val chapters = (1..count).map {
        BackupChapter(number = "$it", url = "${PROGRESS_WORK.url}/chapter/$it", isRead = true, lastReadDate = 9_000)
    }
    return BackupFile(mangas = listOf(libraryParent().toBackup(chapters)))
}

private suspend fun ProgressRuntimeFixture.seedNumbered(count: Int) = parent().also { owner ->
    for (number in 1..count) {
        chapter(
            librarySavedChapter(owner, "${owner.url}/chapter/$number").copy(
                number = "$number",
                isRead = number <= 50,
                isBookmarked = number == 10,
                lastReadDate = if (number == 95) 5_000 else 0,
                isDownloaded = number == 20,
                localImagePaths = if (number == 20) listOf("/device/ch20.cbz") else emptyList(),
            ),
        )
    }
}

private enum class Conflict { SAVED_WORK, SAVED_CHAPTER, NATIVE_WORK, NATIVE_CHAPTER, HISTORY, HISTORY_ID, BLANK_HISTORY }

private suspend fun ProgressRuntimeFixture.seedConflict(family: ProgressSavedFamily, conflict: Conflict) {
    val progress = db.readerProgressDao()
    when (conflict) {
        Conflict.SAVED_WORK -> parent(libraryParent(PROGRESS_PREVIOUS.work.url))
        Conflict.SAVED_CHAPTER -> chapter(librarySavedChapter(family.work, PROGRESS_PREVIOUS.chapterUrl))
        Conflict.NATIVE_WORK, Conflict.NATIVE_CHAPTER -> {
            progress.ensureSnapshot(PROGRESS_WORK.api, PROGRESS_WORK.url, PROGRESS_CHAPTER.chapterUrl)
            val workUrl = if (conflict == Conflict.NATIVE_WORK) PROGRESS_PREVIOUS.work.url else PROGRESS_WORK.url
            progress.ensureSnapshot(PROGRESS_WORK.api, workUrl, PROGRESS_PREVIOUS.chapterUrl)
        }
        Conflict.HISTORY -> {
            db.backupDao().insertHistoryRow(libraryHistory(family.work))
            db.backupDao().insertHistoryRow(libraryHistory(family.work).copy(mangaUrl = PROGRESS_PREVIOUS.work.url))
        }
        Conflict.HISTORY_ID -> db.backupDao().insertHistoryRow(libraryHistory(family.work, family.work.id + 1))
        Conflict.BLANK_HISTORY -> db.backupDao().insertHistoryRow(libraryHistory(family.work).copy(api = ""))
    }
}

internal fun ProgressRuntimeFixture.backupWriter() = BackupMergeWriter(owners, legacySettings)

internal suspend fun ProgressRuntimeFixture.mergeBackup(document: BackupFile): List<BackupOwnedMerge> {
    val writer = backupWriter()
    val batch = writer.batch(document)
    return batch.groups.map { writer.importGroup(batch, it) }
}

internal fun backupDocument(page: Int? = null, readAt: Long = 9_000): BackupFile {
    val chapter = BackupChapter(
        url = PROGRESS_CHAPTER.chapterUrl,
        isRead = true,
        lastReadDate = readAt,
        resumePage = page,
    )
    return BackupFile(mangas = listOf(libraryParent().toBackup(listOf(chapter))))
}
