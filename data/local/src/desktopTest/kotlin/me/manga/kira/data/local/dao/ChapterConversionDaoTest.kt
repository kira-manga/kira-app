package me.manga.kira.data.local.dao

import kotlinx.coroutines.flow.first
import me.manga.kira.data.local.entity.ChapterArtifactEntity
import me.manga.kira.data.local.entity.ChapterConversionRoster
import me.manga.kira.data.local.entity.ChapterConversionSource
import me.manga.kira.data.local.entity.ChapterNotification
import me.manga.kira.data.local.entity.claimOrNull
import me.manga.kira.presentation.features.download.data.DownloadingState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Existing real Room/SQL-trigger fixture; no modeled transactions or fabricated schema. */
class ChapterConversionDaoTest {
    @Test
    fun everyRequiredConversionWriteRollsBackTheWholePublication() = completionTest {
        for (boundary in listOf("saved", "mirror", "history", "receipt")) {
            val original = conversionSeed()
            val mirror = conversionMirror(original)
            val claim = assertNotNull(db.chapterArtifactDao().claimConversion(original.saved, TOKEN, roster(original)))
            executeWhileClosed(conversionTrigger(boundary, original))
            assertFailsWith<Exception> {
                db.chapterArtifactCommitDao().commitConversion(claim, original.saved, listOf(canonical(original)), SIZE)
            }
            assertUnchanged(original)
            assertEquals(mirror, db.notificationDao().getAllNotifications().first().single { it.chapterId == original.saved.id })
            assertNull(db.chapterArtifactDao().get(original.saved.id)?.committedToken)
            assertEquals(ChapterConversionOutcome.NOT_COMMITTED,
                db.chapterArtifactCommitDao().readConversionOutcome(claim, canonical(original), SIZE))
        }
    }

    @Test
    fun historyEvictionDuringConversionDoesNotRecreateItAndReadbackRemainsCommitted() = completionTest {
        val original = conversionSeed()
        val mirror = conversionMirror(original)
        val claim = assertNotNull(db.chapterArtifactDao().claimConversion(original.saved, TOKEN, roster(original)))
        dao.deleteByChapterId(original.saved.id)
        assertTrue(db.chapterArtifactDao().canPublish(claim))
        assertTrue(db.chapterArtifactCommitDao().commitConversion(claim, original.saved, listOf(canonical(original)), SIZE))
        assertEquals(ChapterConversionOutcome.COMMITTED,
            db.chapterArtifactCommitDao().readConversionOutcome(claim, canonical(original), SIZE))
        assertNull(dao.getDownloadByChapter(original.saved.id))
        assertEquals(original.saved.copy(localImagePaths = listOf(canonical(original))), db.chapterDao().getChapterByIdSuspend(original.saved.id))
        assertEquals(mirror.copy(localImagePaths = listOf(canonical(original))), db.notificationDao().getAllNotifications().first().single())
    }

    @Test
    fun replacementOwnerLedgerSizeAndMirrorDisagreementCannotAuthorizeCleanup() = completionTest {
        for (boundary in listOf("owner", "ledger", "api", "size", "mirror", "retired")) {
            val original = conversionSeed()
            val mirror = conversionMirror(original)
            val claim = assertNotNull(db.chapterArtifactDao().claimConversion(original.saved, TOKEN, roster(original)))
            val commits = db.chapterArtifactCommitDao()
            assertTrue(commits.commitConversion(claim, original.saved, listOf(canonical(original)), SIZE))
            when (boundary) {
                "owner" -> db.backupDao().updateChapterRow(original.saved.copy(url = "replacement-owner"))
                "ledger" -> dao.insert(original.download.copy(id = 0, state = DownloadingState.QUEUED))
                "api" -> dao.insert(original.download.copy(api = "replacement-api", sizeBytes = SIZE))
                "size" -> dao.updateSize(original.saved.id, SIZE + 1)
                "mirror" -> commits.writeNotification(ArtifactReadableUpdate(mirror.id, false, emptyList()))
                "retired" -> db.chapterArtifactDao().update(assertNotNull(commits.artifact(original.saved.id)).copy(
                    retiredRelativePath = "_restored/$TOKEN/chapter.cbz",
                ))
            }
            assertEquals(ChapterConversionOutcome.UNKNOWN, commits.readConversionOutcome(claim, canonical(original), SIZE))
            assertEquals(claim, db.chapterArtifactDao().get(original.saved.id)?.claimOrNull())
        }
    }

    @Test
    fun exactRosterIsPartOfAdmissionAndSurvivesReopenUntilKnownRelease() = completionTest {
        val original = conversionSeed()
        val claim = assertNotNull(db.chapterArtifactDao().claimConversion(original.saved, TOKEN, roster(original)))
        reopen()
        assertEquals(claim, db.chapterArtifactDao().get(original.saved.id)?.claimOrNull())
        assertNull(db.chapterArtifactDao().claimConversion(original.saved, NEXT, roster(original)))
        assertEquals(ChapterConversionOutcome.NOT_COMMITTED,
            db.chapterArtifactCommitDao().readConversionOutcome(claim, canonical(original), null))
        assertEquals(1, db.chapterArtifactDao().revoke(original.saved.id, TOKEN))
        assertEquals(1, db.chapterArtifactDao().release(original.saved.id, TOKEN))
        assertNull(db.chapterArtifactDao().get(original.saved.id)?.conversionSourceRoster)
        assertNotNull(db.chapterArtifactDao().claimConversion(original.saved, NEXT, roster(original)))
    }

    @Test
    fun activeAttemptsAndExplicitRestoredReferencesRejectConversionAdmission() = completionTest {
        for (state in listOf(DownloadingState.QUEUED, DownloadingState.RUNNING, DownloadingState.DOWNLOADED, DownloadingState.COMPRESSING)) {
            val original = conversionSeed(state)
            assertNull(db.chapterArtifactDao().claimConversion(original.saved, TOKEN, roster(original)))
            assertUnchanged(original)
        }
        val restored = conversionSeed()
        db.chapterArtifactDao().insert(ChapterArtifactEntity(
            restored.saved.id, restored.saved.mangaId, restored.saved.url, committedRelativePath = "_restored/$TOKEN/chapter.cbz",
        ))
        assertNull(db.chapterArtifactDao().claimConversion(restored.saved, TOKEN, roster(restored)))
        assertUnchanged(restored)
        val foreignApi = conversionSeed()
        dao.insert(foreignApi.download.copy(api = "replacement-api"))
        assertNull(db.chapterArtifactDao().claimConversion(foreignApi.saved, TOKEN, roster(foreignApi)))
    }

    private suspend fun ChapterDownloadCompletionFixture.conversionSeed(
        state: DownloadingState = DownloadingState.SUCCESS,
    ): SeededDownload {
        val original = seed(state, emptyList())
        val path = "/sandbox/manga/${original.saved.mangaId}/chapter_${original.saved.id}/page.png"
        val saved = original.saved.copy(isDownloaded = true, localImagePaths = listOf(path))
        db.backupDao().updateChapterRow(saved)
        return original.copy(saved = saved)
    }

    private suspend fun ChapterDownloadCompletionFixture.conversionMirror(original: SeededDownload): ChapterNotification {
        db.notificationDao().insertNotificationsList(listOf(ChapterNotification(
            api = "test", language = "en", mangaId = original.saved.mangaId, mangaTitle = "Manga", mangaImageUrl = "cover",
            mangaUrl = "manga", chapterId = original.saved.id, chapterNumber = "1", chapterUrl = original.saved.url,
            isRead = true, isDownloaded = true, localImagePaths = original.saved.localImagePaths,
        )))
        return db.notificationDao().getAllNotifications().first().single { it.chapterId == original.saved.id }
    }

    private fun conversionTrigger(boundary: String, original: SeededDownload): String {
        val id = original.saved.id
        val table = mapOf("saved" to "saved_chapters", "mirror" to "notifications", "history" to "chapter_downloads", "receipt" to "chapter_artifacts").getValue(boundary)
        val mutation = if (boundary == "history") "INSERT" else "UPDATE"
        val key = if (boundary == "saved") "id" else "chapterId"
        val action = if (boundary == "history") "RAISE(ABORT, 'conversion rollback')" else "RAISE(IGNORE)"
        return "CREATE TRIGGER conversion_${boundary}_$id BEFORE $mutation ON $table " +
            "WHEN NEW.$key = $id BEGIN SELECT $action; END"
    }

    private fun roster(original: SeededDownload) = ChapterConversionRoster.encode(
        original.saved.localImagePaths.map { ChapterConversionSource(it, "page.png") },
    )
    private fun canonical(original: SeededDownload) = "/sandbox/manga/${original.saved.mangaId}/chapter_${original.saved.id}/chapter_${original.saved.id}.cbz"
    private companion object {
        const val TOKEN = "11111111-1111-4111-8111-111111111111"
        const val NEXT = "22222222-2222-4222-8222-222222222222"
        const val SIZE = 4321L
    }
}
