package me.manga.kira.presentation.features.download.domain.clean

import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import me.manga.kira.platform.filesystem.AppFileSystem
import me.manga.kira.platform.filesystem.chapterDir
import okio.FileSystem
import okio.Path

/**
 * Regression tests for [DownloadManifestStore] — the durable state the iOS background engine's
 * resume path stands on. A manifest that fails to round-trip breaks force-quit recovery; an
 * [DownloadManifestStore.incrementAttempt] that bumps the wrong page (or fails to persist) breaks
 * the bounded-retry contract [BackgroundReconciler] enforces; and a corrupt manifest must read as
 * `null` (the engine then re-resolves) rather than throw. Runs against the real okio filesystem
 * under a throwaway temp root, mirroring the `:data` `TempDirAppFileSystem` pattern.
 */
class DownloadManifestStoreTest {

    private val appFs = TempDirAppFileSystem()
    private val store = DownloadManifestStore(appFs)

    @AfterTest
    fun tearDown() = appFs.cleanUp()

    private fun manifest() = DownloadManifest(
        mangaId = 7L,
        chapterId = 42L,
        api = "azora",
        pages = listOf(
            ManifestPage(index = 0, url = "https://cdn.example/p0.webp", headers = mapOf("Referer" to "https://example")),
            ManifestPage(index = 1, url = "https://cdn.example/p1.webp", headers = emptyMap(), attempts = 2),
        ),
    )

    @Test
    fun writeThenRead_roundTripsPagesHeadersAndAttempts() {
        store.write(manifest())
        assertEquals(manifest(), store.read(7L, 42L))
    }

    @Test
    fun read_missingManifest_returnsNull() {
        assertNull(store.read(7L, 42L))
    }

    @Test
    fun read_corruptManifest_returnsNull_insteadOfThrowing() {
        // A torn write / disk corruption must degrade to "no manifest" (the engine re-resolves),
        // never to a throw that would kill the pump.
        val dir = appFs.chapterDir(7L, 42L)
        appFs.fileSystem().createDirectories(dir)
        appFs.fileSystem().write(dir / "manifest.json") { writeUtf8("{\"mangaId\": 7, \"chapt") }
        assertNull(store.read(7L, 42L))
    }

    @Test
    fun read_toleratesUnknownKeys_forwardCompatibility() {
        // A manifest written by a NEWER app version (extra fields) must still resume on this one —
        // the Json config's ignoreUnknownKeys is a load-bearing part of the format.
        val dir = appFs.chapterDir(7L, 42L)
        appFs.fileSystem().createDirectories(dir)
        appFs.fileSystem().write(dir / "manifest.json") {
            writeUtf8(
                """{"mangaId":7,"chapterId":42,"api":"azora","futureTopLevel":true,""" +
                    """"pages":[{"index":0,"url":"u","headers":{},"attempts":1,"futureField":"x"}]}""",
            )
        }
        val read = store.read(7L, 42L)
        assertEquals(
            DownloadManifest(7L, 42L, "azora", listOf(ManifestPage(0, "u", emptyMap(), attempts = 1))),
            read,
        )
    }

    @Test
    fun incrementAttempt_bumpsOnlyTheTargetPage_persists_andReturnsNewCount() {
        store.write(manifest())
        val newCount = store.incrementAttempt(7L, 42L, pageIndex = 0)
        assertEquals(1, newCount)
        // Re-read from DISK: the bump must be durable (it is what survives a force-quit and keeps
        // the retry budget bounded), and page 1's pre-existing count must be untouched.
        val reread = store.read(7L, 42L)!!
        assertEquals(1, reread.pages.first { it.index == 0 }.attempts)
        assertEquals(2, reread.pages.first { it.index == 1 }.attempts)
    }

    @Test
    fun incrementAttempt_accumulatesAcrossCalls() {
        store.write(manifest())
        store.incrementAttempt(7L, 42L, pageIndex = 0)
        store.incrementAttempt(7L, 42L, pageIndex = 0)
        assertEquals(3, store.incrementAttempt(7L, 42L, pageIndex = 0))
        assertEquals(3, store.read(7L, 42L)!!.pages.first { it.index == 0 }.attempts)
    }

    @Test
    fun incrementAttempt_missingManifest_returnsZero() {
        // Retry accounting for a chapter whose manifest is gone (deleted mid-flight) degrades to 0 —
        // the engine's reconcile then re-resolves instead of counting against a ghost budget.
        assertEquals(0, store.incrementAttempt(7L, 42L, pageIndex = 0))
    }

    @Test
    fun incrementAttempt_unknownPageIndex_returnsZero_andLeavesPagesUnchanged() {
        // A stale transport callback can carry a page index from a PREVIOUS manifest generation
        // (retry-after-re-resolve). It must not corrupt the current manifest's budget.
        store.write(manifest())
        assertEquals(0, store.incrementAttempt(7L, 42L, pageIndex = 99))
        assertEquals(manifest().pages, store.read(7L, 42L)!!.pages)
    }

    @Test
    fun exists_isACheapProbe_trackingWriteAndDelete() {
        // The resolve-ahead window check runs per pump — it uses exists() (fs probe, no JSON parse)
        // and must track the manifest lifecycle exactly.
        assertFalse(store.exists(7L, 42L))
        store.write(manifest())
        assertTrue(store.exists(7L, 42L))
        store.delete(7L, 42L)
        assertFalse(store.exists(7L, 42L))
    }

    @Test
    fun delete_removesManifest_andIsIdempotent() {
        store.write(manifest())
        store.delete(7L, 42L)
        assertNull(store.read(7L, 42L))
        store.delete(7L, 42L) // second delete of a missing manifest must be a silent no-op
        assertNull(store.read(7L, 42L))
    }

    /** Real okio filesystem under a throwaway temp root (mirrors `:data`'s TempDirAppFileSystem). */
    private class TempDirAppFileSystem : AppFileSystem {
        private val fs: FileSystem = FileSystem.SYSTEM
        private val root: Path =
            FileSystem.SYSTEM_TEMPORARY_DIRECTORY /
                "yami-manifest-test-${Random.nextLong().toString().trimStart('-')}"
        override val filesDir: Path = root
        override val cacheDir: Path = root / "cache"
        override fun fileSystem(): FileSystem = fs
        fun cleanUp() = fs.deleteRecursively(root, mustExist = false)
    }
}
