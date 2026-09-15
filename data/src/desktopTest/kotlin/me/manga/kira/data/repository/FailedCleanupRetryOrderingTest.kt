package me.manga.kira.data.repository

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import me.manga.kira.data.local.entity.ChapterArtifactOperation
import me.manga.kira.platform.filesystem.chapterDir
import me.manga.kira.presentation.features.download.data.DownloadingState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Composed App77 conditional Retry against App78's real cleanup admission and terminal transaction. */
class FailedCleanupRetryOrderingTest {
    @Test
    fun deleteFirstRefusesCapturedRetryDuringCleanupAfterSettlementAndAfterReopen() = downloadRecoveryTest {
        val original = seed(DownloadingState.FAILED)
        val captured = download(original)
        val runtime = artifactRuntime
        val page = appFileSystem.chapterDir(original.saved.mangaId, original.saved.id) / "image_0.png"
        fs.write(page) { writeUtf8("retained attempt page") }
        val admitted = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var retryPreparations = 0
        coroutineScope {
            val deleting = async {
                runtime.downloads.deleteAttempt(captured) { cleanup ->
                    assertEquals(ChapterArtifactOperation.FAILED_CLEANUP, cleanup.operation)
                    admitted.complete(Unit)
                    release.await()
                }
            }
            try {
                admitted.await()
                assertNull(runtime.downloads.retry(captured) { retryPreparations++ })
                assertEquals(0, retryPreparations, "Rejected Retry must not prepare a replacement manifest")
                assertEquals(captured, download(original))
                assertTrue(fs.exists(page))
                release.complete(Unit)
                assertTrue(deleting.await())
            } finally {
                release.complete(Unit)
            }
        }
        assertNull(dao.getDownloadByChapter(original.saved.id))
        assertFalse(fs.exists(page))
        assertNull(runtime.downloads.retry(captured) { retryPreparations++ })
        assertNull(dao.getDownloadByChapter(original.saved.id))
        reopen()
        assertNull(artifactRuntime.downloads.retry(captured) { retryPreparations++ })
        assertNull(dao.getDownloadByChapter(original.saved.id))
        assertEquals(0, retryPreparations)
    }

    @Test
    fun conditionalRetryFirstRejectsOldDeleteBeforeStopAndPreservesReplacementPages() = downloadRecoveryTest {
        val original = seed(DownloadingState.FAILED)
        val captured = download(original)
        val runtime = artifactRuntime
        val page = appFileSystem.chapterDir(original.saved.mangaId, original.saved.id) / "image_0.png"
        fs.write(page) { writeUtf8("resumable retry page") }
        val retry = assertNotNull(runtime.downloads.retry(captured))
        val replacement = download(original)
        assertTrue(replacement.id != captured.id)
        var stops = 0

        assertFalse(runtime.downloads.deleteAttempt(captured) { stops++ })

        assertEquals(0, stops)
        assertEquals(replacement, download(original))
        assertTrue(runtime.dao.canPublish(retry))
        assertEquals("resumable retry page", fs.read(page) { readUtf8() })
    }
}
