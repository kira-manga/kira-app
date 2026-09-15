@file:Suppress("MagicNumber")

package me.manga.kira.data.backup

import kotlinx.serialization.encodeToString
import me.manga.kira.data.backup.model.BackupChapter
import me.manga.kira.data.backup.model.BackupFile
import me.manga.kira.data.backup.model.BackupHistoryItem
import me.manga.kira.data.backup.model.BackupManga
import me.manga.kira.data.repository.DownloadRecoveryFixture
import me.manga.kira.data.repository.downloadRecoveryTest
import me.manga.kira.data.repository.recoveryTestPng
import me.manga.kira.platform.backup.BackupDownloadLimits
import me.manga.kira.platform.backup.BackupImportLimitExceeded
import me.manga.kira.platform.backup.BackupImportPolicy
import me.manga.kira.platform.backup.BackupImportStaging
import me.manga.kira.platform.backup.BackupZipWriter
import me.manga.kira.platform.backup.InvalidBackupArchive
import me.manga.kira.platform.media.DesktopPageMediaInspector
import me.manga.kira.platform.media.PageInspection
import me.manga.kira.platform.media.PageInspectionRejection
import me.manga.kira.platform.media.PageMediaInspector
import okio.Buffer
import okio.Path
import okio.Path.Companion.toPath
import okio.use
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Preflight only: reuse the real Room/filesystem fixture; this does not claim publication coverage. */
class BackupArchivePreflightTest {
    private val native = DesktopPageMediaInspector()

    @Test
    fun validPickedArchiveProducesOwnedSnapshotsAndReleasesTheOuterInput() = downloadRecoveryTest {
        val cbz = archive("nested/1.png" to recoveryTestPng())
        val original = writeBackup(document(), "downloads/0.cbz" to cbz)
        val staging = BackupImportStaging(appFileSystem)
        val picked = staging.stage(null, {}) { fs.source(original) }
        BackupArchivePreflight(appFileSystem, staging, native).prepare(picked) {}.use { plan ->
            assertFalse(fs.exists(picked.toPath()))
            assertEquals(setOf("downloads/0.cbz"), plan.manifest.references)
            val download = plan.downloads.getValue("downloads/0.cbz")
            assertEquals(cbz.size.toLong(), download.size)
            assertContentEquals(cbz, fs.read(download.path) { readByteArray() })
            assertTrue(fs.exists(original), "the user's original is never owned")
        }
        assertNoSnapshots()
    }

    @Test
    fun invalidLastCbzCannotMutateExistingRoomLedgerOrChapterFiles() = downloadRecoveryTest {
        val retained = seed(isDownloaded = true)
        val before = db.backupDao().getAllSavedManga()
        val selected = writeBackup(
            document("downloads/0.cbz", "downloads/1.cbz"),
            "downloads/0.cbz" to archive("1.png" to recoveryTestPng()),
            "downloads/1.cbz" to archive("1.png" to "not an image".encodeToByteArray()),
        )
        assertFailsWith<InvalidBackupArchive> { preflight().prepare(selected.toString()) {} }
        assertEquals(before, db.backupDao().getAllSavedManga())
        assertEquals(retained.saved, saved(retained))
        assertEquals(retained.download, download(retained))
        assertRetainedFiles(retained)
        assertTrue(fs.exists(selected))
        assertNoSnapshots()
    }

    @Test
    fun cancellationOnTheLastPageCleansEarlierValidatedSnapshotsAndPreservesLiveState() = downloadRecoveryTest {
        val retained = seed(isDownloaded = true)
        val cbz = archive("1.png" to recoveryTestPng())
        val selected = writeBackup(document("downloads/0.cbz", "downloads/1.cbz"), "downloads/0.cbz" to cbz, "downloads/1.cbz" to cbz)
        val cancellation = CancellationException("cancel second page")
        var inspected = 0
        val inspector = object : PageMediaInspector by native {
            override fun inspect(encoded: ByteArray): PageInspection {
                if (++inspected == 2) throw cancellation
                return native.inspect(encoded)
            }
        }
        assertSame(cancellation, assertFailsWith<CancellationException> { preflight(inspector = inspector).prepare(selected.toString()) {} })
        assertEquals(2, inspected)
        assertEquals(retained.saved, saved(retained))
        assertEquals(retained.download, download(retained))
        assertRetainedFiles(retained)
        assertNoSnapshots()
    }

    @Test
    fun perCbzAndAggregateStagedAndExpandedBudgetsRejectIndividuallyValidPages() = downloadRecoveryTest {
        val page = recoveryTestPng()
        val cbz = archive("1.png" to page)
        val selected = writeBackup(document("downloads/0.cbz", "downloads/1.cbz"), "downloads/0.cbz" to cbz, "downloads/1.cbz" to cbz)
        val limits = listOf(
            BackupDownloadLimits(maxCbzBytes = cbz.size.toLong() - 1),
            BackupDownloadLimits(maxStagedBytes = 2L * cbz.size - 1),
            BackupDownloadLimits(maxExpandedBytesPerCbz = page.size.toLong() - 1),
            BackupDownloadLimits(maxExpandedBytes = 2L * page.size - 1),
        )
        for (limit in limits) {
            assertFailsWith<BackupImportLimitExceeded>(limit.toString()) {
                preflight(BackupImportPolicy(downloads = limit)).prepare(selected.toString()) {}
            }
            assertNoSnapshots()
        }
    }

    @Test
    fun innerEntryLimitsAlsoCoverMetadataAndImplicitDirectories() = downloadRecoveryTest {
        val policy = BackupImportPolicy(downloads = BackupDownloadLimits(maxInnerEntries = 1))
        for (cbz in listOf(archive("1.png" to recoveryTestPng(), "metadata.txt" to byteArrayOf(1)), archive("pages/1.png" to recoveryTestPng()))) {
            val selected = writeBackup(document(), "downloads/0.cbz" to cbz)
            assertFailsWith<BackupImportLimitExceeded> { preflight(policy).prepare(selected.toString()) {} }
            assertNoSnapshots()
        }
    }

    @Test
    fun nonPageEntriesAlsoConsumeExpandedBytesAndMustMatchTheirCrc() = downloadRecoveryTest {
        val page = recoveryTestPng()
        val cbz = archive("notes.txt" to byteArrayOf(1), "1.png" to page)
        val selected = writeBackup(document(), "downloads/0.cbz" to cbz)
        val policy = BackupImportPolicy(downloads = BackupDownloadLimits(maxExpandedBytesPerCbz = page.size.toLong()))
        assertFailsWith<BackupImportLimitExceeded> { preflight(policy).prepare(selected.toString()) {} }
        assertNoSnapshots()
        cbz[30 + "notes.txt".length] = 2
        writeBackup(document(), "downloads/0.cbz" to cbz)
        assertFailsWith<InvalidBackupArchive> { preflight().prepare(selected.toString()) {} }
        assertNoSnapshots()
    }

    @Test
    fun innerCrcCorruptionEmptyArchivesAndNonCbzPayloadsAreRejected() = downloadRecoveryTest {
        val badCrc = archive("1.png" to recoveryTestPng()).also { it[30 + "1.png".length] = 0 }
        for (cbz in listOf(badCrc, archive(), byteArrayOf(1, 2, 3), archive("notes.txt" to byteArrayOf(1)))) {
            val selected = writeBackup(document(), "downloads/0.cbz" to cbz)
            assertFailsWith<InvalidBackupArchive> { preflight().prepare(selected.toString()) {} }
            assertNoSnapshots()
        }
    }

    @Test
    fun missingUnreferencedMisnamedAndAliasedDownloadReferencesAreRejected() = downloadRecoveryTest {
        val cbz = archive("1.png" to recoveryTestPng())
        val invalid = listOf(
            document("downloads/1.cbz"),
            document("downloads/0.cbz", "downloads/0.cbz"),
            document("downloads/00.cbz"),
            document("downloads/../0.cbz"),
            document("/downloads/0.cbz"),
            document("downloads/0.zip"),
            document().copy(includesDownloads = false),
            document().copy(mangas = emptyList()),
        )
        for (manifest in invalid) {
            val selected = writeBackup(manifest, "downloads/0.cbz" to cbz)
            assertFailsWith<InvalidBackupArchive> { preflight().prepare(selected.toString()) {} }
            assertNoSnapshots()
        }
    }

    @Test
    fun formatIdentityAndDateFailuresAreDetectedBeforeReturningAPlan() = downloadRecoveryTest {
        val base = document().let { it.copy(includesDownloads = false, mangas = it.mangas.map { manga -> manga.copy(chapters = emptyList()) }) }
        val manga = base.mangas.single()
        val invalid = listOf(
            base.copy(formatVersion = 0),
            base.copy(mangas = listOf(manga, manga)),
            base.copy(mangas = listOf(manga.copy(url = " "))),
            base.copy(mangas = listOf(manga.copy(chapters = listOf(BackupChapter(url = "c", dateEpochDay = Long.MAX_VALUE))))),
            base.copy(mangas = listOf(manga.copy(chapters = listOf(BackupChapter(url = "c", resumePage = -1))))),
            base.copy(history = listOf(BackupHistoryItem(mangaUrl = "m", lastReadPage = -1))),
            base.copy(history = listOf(BackupHistoryItem(mangaUrl = "m"), BackupHistoryItem(mangaUrl = "m"))),
        )
        for (manifest in invalid) {
            val selected = writeBackup(manifest)
            assertFailsWith<InvalidBackupArchive> { preflight().prepare(selected.toString()) {} }
            assertNoSnapshots()
        }
        val future = writeBackup(base.copy(formatVersion = 2))
        assertFailsWith<BackupFormatTooNew> { preflight().prepare(future.toString()) {} }
        assertNoSnapshots()
    }

    @Test
    fun exactAggregateLimitsAndMetadataOnlyBackupsRemainImportable() = downloadRecoveryTest {
        val page = recoveryTestPng()
        val cbz = archive("1.png" to page)
        val selected = writeBackup(document("downloads/0.cbz", "downloads/1.cbz"), "downloads/0.cbz" to cbz, "downloads/1.cbz" to cbz)
        val limits = BackupDownloadLimits(
            maxCbzBytes = cbz.size.toLong(), maxStagedBytes = 2L * cbz.size,
            maxExpandedBytesPerCbz = page.size.toLong(), maxExpandedBytes = 2L * page.size,
        )
        preflight(BackupImportPolicy(downloads = limits)).prepare(selected.toString()) {}.use { assertEquals(2, it.downloads.size) }
        assertNoSnapshots()
        val metadataOnly = writeBackup(BackupFile())
        preflight().prepare(metadataOnly.toString()) {}.use { assertTrue(it.downloads.isEmpty()) }
        assertNoSnapshots()
    }

    @Test
    fun nativeResourceRejectionRemainsTypedAndNoSnapshotEscapes() = downloadRecoveryTest {
        val selected = writeBackup(document(), "downloads/0.cbz" to archive("1.png" to recoveryTestPng()))
        val inspector = object : PageMediaInspector by native {
            override fun inspect(encoded: ByteArray): PageInspection = PageInspection.Rejected(PageInspectionRejection.SOURCE_PIXELS)
        }
        assertFailsWith<BackupImportLimitExceeded> { preflight(inspector = inspector).prepare(selected.toString()) {} }
        assertNoSnapshots()
    }

    private fun DownloadRecoveryFixture.preflight(
        policy: BackupImportPolicy = BackupImportPolicy(),
        inspector: PageMediaInspector = native,
    ): BackupArchivePreflight = BackupArchivePreflight(appFileSystem, BackupImportStaging(appFileSystem, policy), inspector, policy)

    private fun DownloadRecoveryFixture.writeBackup(document: BackupFile, vararg entries: Pair<String, ByteArray>): Path {
        fs.createDirectories(appFileSystem.cacheDir)
        val path = appFileSystem.cacheDir / "user-selection.zip"
        fs.write(path) { write(archive("backup.json" to backupJson.encodeToString(document).encodeToByteArray(), *entries)) }
        return path
    }

    private fun DownloadRecoveryFixture.assertNoSnapshots() {
        for (name in listOf("backup_import", "backup_preflight")) {
            val parent = appFileSystem.cacheDir / name
            if (fs.exists(parent)) assertTrue(fs.list(parent).isEmpty(), "owned $name directories must be released")
        }
    }

    private fun document(vararg references: String = arrayOf("downloads/0.cbz")): BackupFile =
        BackupFile(
            includesDownloads = true,
            mangas = listOf(
                BackupManga(
                    api = "source", url = "https://example.test/imported", title = "Imported",
                    chapters = references.mapIndexed { index, reference -> BackupChapter(url = "chapter-$index", downloadEntry = reference) },
                ),
            ),
        )

    private fun archive(vararg entries: Pair<String, ByteArray>): ByteArray {
        val buffer = Buffer()
        BackupZipWriter(buffer).apply {
            entries.forEach { (name, bytes) -> writeEntryBytes(name, bytes) }
            finish()
        }
        return buffer.readByteArray()
    }
}
