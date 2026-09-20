package me.manga.kira.data.backup

import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.encodeToString
import me.manga.kira.data.backup.model.BackupFile
import me.manga.kira.data.backup.model.BackupHistoryItem
import me.manga.kira.data.backup.model.BackupManga
import me.manga.kira.data.repository.progress.ProgressRuntimeFixture
import me.manga.kira.data.repository.progress.progressValue
import me.manga.kira.platform.backup.BackupImportStaging
import me.manga.kira.platform.backup.BackupZipWriter
import me.manga.kira.platform.media.DesktopPageMediaInspector
import okio.Buffer
import okio.use
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/** Source-join controls for typed ownership after whole-archive admission. */
class BackupOwnershipAdmissionJoinTest {
    @Test
    fun sameTitleDifferentPortableOwnersRemainDistinctAfterBoundedImport() = runTest {
        ProgressRuntimeFixture().use { runtime ->
            val files = BackupTestFileSystem("same-title")
            try {
                val document = twoWorks()
                val repository = backupTestRepository(runtime.db, files, BackupMergeWriter(runtime.owners, runtime.legacySettings))
                val result = repository.importBackup(files.writeManifest(document)).progressValue()
                assertEquals(2, result.mangasAdded)
                val saved = runtime.db.backupDao().getAllSavedManga()
                assertEquals(document.mangas.map { it.url }.toSet(), saved.map { it.url }.toSet())
                assertEquals(2, saved.map { it.id }.toSet().size)
                assertEquals(setOf("Same title"), saved.map { it.title }.toSet())
            } finally {
                files.cleanUp()
            }
        }
    }

    @Test
    fun ownedGroupsCarryAdmittedHistoryTimestampsAcrossEarlierCommits() = runTest {
        ProgressRuntimeFixture().use { runtime ->
            val files = BackupTestFileSystem("admitted-history")
            try {
                val document = twoWorks().let { backup -> backup.copy(history = backup.mangas.map {
                    BackupHistoryItem(api = it.api, mangaUrl = it.url, mangaTitle = it.title, lastReadDateEpochMs = 1_000)
                }) }
                val preflight = BackupArchivePreflight(files, BackupImportStaging(files), DesktopPageMediaInspector())
                preflight.prepare(files.writeManifest(document)) {}.use { plan ->
                    val dates = listOf(LocalDateTime(2026, 9, 1, 1, 2), LocalDateTime(2026, 9, 2, 3, 4))
                    // Sentinel conversion results isolate the handoff without changing JVM-global timezone.
                    val admitted = plan.manifest.history.mapIndexed { index, row -> row.copy(lastReadDate = dates[index]) }
                    assertNotEquals(admitted.first().lastReadDate, plan.manifest.history.first().lastReadDate)
                    val writer = BackupMergeWriter(runtime.owners, runtime.legacySettings)
                    val batch = writer.batch(plan.manifest.document, admitted)
                    batch.groups.forEach { writer.importGroup(batch, it) }
                    val history = runtime.db.backupDao().getAllHistoryOnce().associateBy { it.mangaUrl }
                    admitted.forEach { assertEquals(it.lastReadDate, history.getValue(it.mangaUrl).lastReadDate) }
                }
            } finally {
                files.cleanUp()
            }
        }
    }

    private fun twoWorks() = BackupFile(mangas = (1..2).map { index ->
        BackupManga(api = "source", url = "https://current.test/work/$index", title = "Same title")
    })

    private fun BackupTestFileSystem.writeManifest(document: BackupFile): String {
        val archive = Buffer()
        BackupZipWriter(archive).apply {
            writeEntryBytes(BACKUP_JSON_ENTRY, backupJson.encodeToString(document).encodeToByteArray())
            finish()
        }
        fileSystem().createDirectories(cacheDir)
        val selected = cacheDir / "selected.zip"
        fileSystem().write(selected) { writeAll(archive) }
        return selected.toString()
    }
}
