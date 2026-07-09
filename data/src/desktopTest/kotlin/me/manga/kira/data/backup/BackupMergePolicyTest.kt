package me.manga.kira.data.backup

import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import me.manga.kira.data.local.entity.HistoryItemD
import me.manga.kira.data.local.entity.SavedChapterEntity
import me.manga.kira.data.local.entity.SavedMangaEntity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Unit tests for the pure merge decisions in [BackupMergePolicy] — the owner-approved ground
 * rules: a merge never deletes or regresses local data, boolean progress flags OR together,
 * timestamps keep whichever side is more advanced, local metadata wins.
 */
class BackupMergePolicyTest {

    private fun manga(
        id: Long = 7,
        title: String = "Local Title",
        description: String = "local desc",
        imageUrl: String = "https://local/img.png",
        savedTimestamp: Long = 1_000,
        lastOpenTimestamp: Long = 2_000,
        isLiked: Boolean = false,
        isWatchingNow: Boolean = false,
    ) = SavedMangaEntity(
        id = id,
        api = "azora",
        language = "ar",
        url = "https://azora/manga/1",
        imageUrl = imageUrl,
        title = title,
        description = description,
        status = "Ongoing",
        rating = "4.5",
        genres = listOf("action"),
        savedTimestamp = savedTimestamp,
        lastOpenTimestamp = lastOpenTimestamp,
        isLiked = isLiked,
        isWatchingNow = isWatchingNow,
    )

    private fun chapter(
        id: Long = 3,
        isRead: Boolean = false,
        isBookmarked: Boolean = false,
        lastReadDate: Long = 0,
        isDownloaded: Boolean = false,
        localImagePaths: List<String> = emptyList(),
        isNew: Boolean = false,
        fetchedAt: Long = 0,
    ) = SavedChapterEntity(
        id = id,
        mangaId = 7,
        name = "Chapter 1",
        number = "1",
        url = "https://azora/ch/1",
        date = LocalDate(2026, 1, 15),
        isDownloaded = isDownloaded,
        isBookmarked = isBookmarked,
        isRead = isRead,
        isNew = isNew,
        lastReadPage = 0,
        lastReadDate = lastReadDate,
        localImagePaths = localImagePaths,
        fetchedAt = fetchedAt,
    )

    private fun history(lastReadDate: LocalDateTime) = HistoryItemD(
        id = 1,
        api = "azora",
        language = "ar",
        mangaId = 7,
        mangaUrl = "https://azora/manga/1",
        mangaTitle = "Local Title",
        mangaImageUrl = "https://local/img.png",
        chapterUrl = "https://azora/ch/1",
        chapterTitle = "Chapter 1",
        isDownloaded = false,
        lastReadDate = lastReadDate,
        lastReadPage = 4,
        totalPages = 20,
    )

    // --- mergeManga -----------------------------------------------------------------------------

    @Test
    fun mergeManga_ors_engagement_flags_both_directions() {
        val liked = BackupMergePolicy.mergeManga(
            local = manga(isLiked = false, isWatchingNow = true),
            incoming = manga(isLiked = true, isWatchingNow = false),
        )
        assertTrue(liked.isLiked, "incoming like survives")
        assertTrue(liked.isWatchingNow, "local watching-now survives")
    }

    @Test
    fun mergeManga_keeps_local_metadata_and_identity() {
        val merged = BackupMergePolicy.mergeManga(
            local = manga(id = 42, title = "Local Title", description = "local desc"),
            incoming = manga(id = 0, title = "Backup Title", description = "backup desc", imageUrl = "https://backup/img.png"),
        )
        assertEquals(42, merged.id, "local autogen id preserved")
        assertEquals("Local Title", merged.title)
        assertEquals("local desc", merged.description)
        assertEquals("https://local/img.png", merged.imageUrl)
    }

    @Test
    fun mergeManga_advances_lastOpen_and_keeps_earliest_nonzero_saved() {
        val merged = BackupMergePolicy.mergeManga(
            local = manga(savedTimestamp = 5_000, lastOpenTimestamp = 2_000),
            incoming = manga(savedTimestamp = 1_000, lastOpenTimestamp = 9_000),
        )
        assertEquals(9_000, merged.lastOpenTimestamp, "newer open wins")
        assertEquals(1_000, merged.savedTimestamp, "earliest save wins")
    }

    @Test
    fun mergeManga_with_pristine_incoming_changes_nothing() {
        val local = manga(isLiked = true, isWatchingNow = true, savedTimestamp = 1_000, lastOpenTimestamp = 2_000)
        val merged = BackupMergePolicy.mergeManga(
            local = local,
            incoming = manga(isLiked = false, isWatchingNow = false, savedTimestamp = 0, lastOpenTimestamp = 0),
        )
        assertEquals(local, merged, "never regresses local state")
    }

    // --- mergeChapter ---------------------------------------------------------------------------

    @Test
    fun mergeChapter_ors_flags_and_advances_lastReadDate() {
        val merged = BackupMergePolicy.mergeChapter(
            local = chapter(isRead = true, isBookmarked = false, lastReadDate = 100),
            incoming = chapter(isRead = false, isBookmarked = true, lastReadDate = 900),
        )
        assertTrue(merged.isRead, "local read flag survives")
        assertTrue(merged.isBookmarked, "incoming bookmark survives")
        assertEquals(900, merged.lastReadDate, "newer read timestamp wins")
    }

    @Test
    fun mergeChapter_never_clears_read_state() {
        val merged = BackupMergePolicy.mergeChapter(
            local = chapter(isRead = true, isBookmarked = true, lastReadDate = 900),
            incoming = chapter(isRead = false, isBookmarked = false, lastReadDate = 0),
        )
        assertTrue(merged.isRead)
        assertTrue(merged.isBookmarked)
        assertEquals(900, merged.lastReadDate)
    }

    @Test
    fun mergeChapter_keeps_local_download_and_transient_columns() {
        val local = chapter(
            isDownloaded = true,
            localImagePaths = listOf("/local/ch.cbz"),
            isNew = true,
            fetchedAt = 777,
        )
        val merged = BackupMergePolicy.mergeChapter(
            local = local,
            incoming = chapter(isRead = true),
        )
        assertTrue(merged.isDownloaded, "download state stays local")
        assertEquals(listOf("/local/ch.cbz"), merged.localImagePaths)
        assertTrue(merged.isNew, "badge state stays local")
        assertEquals(777, merged.fetchedAt)
        assertEquals(local.id, merged.id, "local autogen id preserved")
    }

    // --- shouldReplaceHistory -------------------------------------------------------------------

    @Test
    fun history_replaced_only_when_incoming_read_is_strictly_newer() {
        val older = LocalDateTime(2026, 1, 1, 10, 0)
        val newer = LocalDateTime(2026, 2, 1, 10, 0)
        assertTrue(BackupMergePolicy.shouldReplaceHistory(local = history(older), incoming = history(newer)))
        assertFalse(BackupMergePolicy.shouldReplaceHistory(local = history(newer), incoming = history(older)))
        assertFalse(
            BackupMergePolicy.shouldReplaceHistory(local = history(newer), incoming = history(newer)),
            "equal timestamps keep local (idempotent re-import)",
        )
    }

    // --- shouldRestoreResumePage ----------------------------------------------------------------

    @Test
    fun resumePage_restored_for_new_chapter_newer_read_or_missing_local_position() {
        assertTrue(
            BackupMergePolicy.shouldRestoreResumePage(
                chapterWasNew = true,
                incomingLastReadDate = 0,
                localLastReadDateBefore = 999,
                localSavedPage = 5,
            ),
            "chapter created from the backup always takes its resume page",
        )
        assertTrue(
            BackupMergePolicy.shouldRestoreResumePage(
                chapterWasNew = false,
                incomingLastReadDate = 900,
                localLastReadDateBefore = 100,
                localSavedPage = 5,
            ),
            "newer backup read state overrides the local position",
        )
        assertTrue(
            BackupMergePolicy.shouldRestoreResumePage(
                chapterWasNew = false,
                incomingLastReadDate = 100,
                localLastReadDateBefore = 900,
                localSavedPage = null,
            ),
            "no local position at all -> take the backup's",
        )
        assertFalse(
            BackupMergePolicy.shouldRestoreResumePage(
                chapterWasNew = false,
                incomingLastReadDate = 100,
                localLastReadDateBefore = 900,
                localSavedPage = 5,
            ),
            "older backup never clobbers a local position",
        )
    }

    // --- minNonZero -----------------------------------------------------------------------------

    @Test
    fun minNonZero_treats_zero_as_unknown() {
        assertEquals(5, BackupMergePolicy.minNonZero(0, 5))
        assertEquals(5, BackupMergePolicy.minNonZero(5, 0))
        assertEquals(3, BackupMergePolicy.minNonZero(5, 3))
        assertEquals(0, BackupMergePolicy.minNonZero(0, 0))
    }
}
