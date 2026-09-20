package me.manga.kira.data.repository.selection

import kotlinx.coroutines.test.runTest
import me.manga.kira.data.local.entity.ChapterArtifactEntity
import me.manga.kira.data.local.entity.ChapterArtifactOperation
import me.manga.kira.data.local.entity.ChapterConversionRoster
import me.manga.kira.data.local.entity.ChapterConversionSource
import me.manga.kira.presentation.features.download.data.DownloadingState
import me.manga.kira.sources.contracts.PreviousHostAuthority
import me.manga.kira.sources.contracts.SourceSelectionUnavailable
import java.io.File
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Real Room participant evidence; synthetic rules supply no catalog verification or native exclusion. */
class StrictSelectionArtifactMigrationTest {
    @Test
    fun provenIdleArtifactsMoveOnlyChapterUrlAndCurrentRetainedCustodyIsANoOp() =
        runTest {
            StrictSelectionMigrationFixture().use { f ->
                val first = f.artifactFamily("first", DownloadingState.QUEUED)
                val tokenOnly = f.artifactFamily("token-only")
                val absent = f.artifactFamily("absent")
                val records = listOf(idleArtifact(first), idleArtifact(tokenOnly).copy(committedRelativePath = null))
                records.forEach { f.db.chapterArtifactDao().insert(it) }
                val bytes = f.artifactBytes(listOf(first, tokenOnly, absent), records)
                val before = f.snapshot()
                f.assertUnknownRefuses(before, bytes)
                f.migrate()
                f.reopen()
                assertEquals(before.withMovedUrls(), f.snapshot())
                assertNull(f.db.chapterArtifactDao().get(absent.chapter.id))
                assertArtifactBytes(bytes)
                f.assertCurrentArtifactNoOp(first.chapter.id, bytes)
            }
        }

    @Test
    fun unsettledOrMismatchedSecondArtifactRefusesBeforeAnyUrlWrite() =
        runTest {
            StrictSelectionMigrationFixture().use { f ->
                val first = f.artifactFamily("first")
                val second = f.artifactFamily("second", DownloadingState.QUEUED)
                listOf(first, second).forEach { f.db.chapterArtifactDao().insert(idleArtifact(it)) }
                f.assertArtifactRefusals(first, second)
            }
        }

    @Test
    fun laterArtifactGuardFailureRollsBackEarlierUrlWritesAndRetainedBytes() =
        runTest {
            StrictSelectionMigrationFixture().use { f ->
                val first = f.artifactFamily("first")
                val second = f.artifactFamily("second")
                val records = listOf(idleArtifact(first), idleArtifact(second))
                records.forEach { f.db.chapterArtifactDao().insert(it) }
                val bytes = f.artifactBytes(listOf(first, second), records)
                f.execute(ignoreSecondArtifactAfterFirstMoved(first, second))
                val before = f.snapshot()
                val progress = SelectionPlanningProgress()
                val failure = assertFailsWith<SourceSelectionUnavailable> { f.migrate(progress = progress) }
                assertEquals("selection URL write count was 0", failure.message)
                assertTrue(progress.snapshot()[SelectionVisit.WRITE] > 0L)
                f.reopen()
                assertEquals(before, f.snapshot())
                assertArtifactBytes(bytes)
            }
        }
}

private suspend fun StrictSelectionMigrationFixture.artifactFamily(
    name: String,
    state: DownloadingState = DownloadingState.SUCCESS,
): StrictSavedFamily {
    val seeded = saved("$STRICT_OLD/$name/work", "$STRICT_OLD/$name/chapter")
    val chapter = seeded.chapter.copy(localImagePaths = listOf(artifactFile("$name-page%2F.bin").absolutePath))
    check(db.backupDao().updateChapterRow(chapter) == 1)
    val family = seeded.copy(chapter = chapter)
    related(family, state)
    anchor(family.work.url, family.chapter.url)
    return family
}

private fun idleArtifact(family: StrictSavedFamily) =
    ChapterArtifactEntity(
        chapterId = family.chapter.id,
        mangaId = family.work.id,
        chapterUrl = family.chapter.url,
        committedToken = "retained:$STRICT_OLD/${family.chapter.id}",
        committedRelativePath = "${family.chapter.id}-committed%2F.cbz",
    )

private fun StrictSelectionMigrationFixture.artifactBytes(
    families: List<StrictSavedFamily>,
    records: List<ChapterArtifactEntity>,
): Map<File, ByteArray> {
    val chapterFiles = families.flatMap { it.chapter.localImagePaths }.map { File(it) }
    val custodyFiles =
        records.flatMap { listOfNotNull(it.committedRelativePath, it.pendingRelativePath, it.retiredRelativePath) }
            .map { artifactFile(it) }
    return (chapterFiles + custodyFiles).associateWith { file ->
        val bytes = byteArrayOf(0, -1) + file.name.encodeToByteArray()
        file.writeBytes(bytes)
        bytes
    }
}

private fun assertArtifactBytes(expected: Map<File, ByteArray>) {
    expected.forEach { (file, bytes) ->
        assertTrue(file.isFile, file.name)
        assertContentEquals(bytes, file.readBytes(), file.name)
    }
}

private suspend fun StrictSelectionMigrationFixture.assertUnknownRefuses(
    before: Map<String, StrictTableImage>,
    bytes: Map<File, ByteArray>,
) {
    val progress = SelectionPlanningProgress()
    assertFailsWith<SourceSelectionUnavailable> {
        migrate(listOf(strictRule(authority = PreviousHostAuthority.UNKNOWN)), progress)
    }
    assertEquals(0L, progress.snapshot()[SelectionVisit.WRITE])
    reopen()
    assertEquals(before, snapshot())
    assertArtifactBytes(bytes)
}

private suspend fun StrictSelectionMigrationFixture.assertCurrentArtifactNoOp(
    chapterId: Long,
    bytes: Map<File, ByteArray>,
) {
    val dao = db.chapterArtifactDao()
    val retained = assertNotNull(dao.get(chapterId)).copy(
        token = "captured-download",
        operation = ChapterArtifactOperation.DOWNLOAD,
        downloadId = assertNotNull(dao.download(chapterId)).id,
    )
    assertEquals(1, dao.update(retained))
    val before = snapshot()
    val progress = SelectionPlanningProgress()
    migrate(listOf(strictRule(authority = PreviousHostAuthority.UNKNOWN)), progress)
    assertEquals(0L, progress.snapshot()[SelectionVisit.WRITE])
    reopen()
    assertEquals(before, snapshot())
    assertArtifactBytes(bytes)
}

private suspend fun StrictSelectionMigrationFixture.assertArtifactRefusals(
    first: StrictSavedFamily,
    second: StrictSavedFamily,
) {
    val downloadId = assertNotNull(db.chapterArtifactDao().download(second.chapter.id)).id
    val roster = ChapterConversionRoster.encode(
        listOf(ChapterConversionSource(second.chapter.localImagePaths.single(), "second-page%2F.bin")),
    )
    refusedArtifacts(idleArtifact(second), first.work.id, downloadId, roster).forEach { (label, retained) ->
        assertEquals(1, db.chapterArtifactDao().update(retained), label)
        val bytes = artifactBytes(listOf(first, second), listOf(idleArtifact(first), retained))
        assertArtifactPreflightRefusal(label, bytes)
    }
}

private suspend fun StrictSelectionMigrationFixture.assertArtifactPreflightRefusal(
    label: String,
    bytes: Map<File, ByteArray>,
) {
    val before = snapshot()
    val progress = SelectionPlanningProgress()
    val failure = assertFailsWith<SourceSelectionUnavailable>(label) { migrate(progress = progress) }
    assertEquals("selection requires a settled exact chapter artifact owner", failure.message, label)
    assertEquals(0L, progress.snapshot()[SelectionVisit.WRITE], label)
    reopen()
    assertEquals(before, snapshot(), label)
    assertArtifactBytes(bytes)
}

private fun refusedArtifacts(
    idle: ChapterArtifactEntity,
    otherMangaId: Long,
    downloadId: Long,
    roster: String,
): List<Pair<String, ChapterArtifactEntity>> =
    listOf(
        "active download with QUEUED row" to idle.copy(
            token = "captured-download", operation = ChapterArtifactOperation.DOWNLOAD, downloadId = downloadId,
        ),
        "token residue" to idle.copy(token = "retained-token"),
        "unknown operation without token" to idle.copy(operation = "unknown-operation"),
        "retiring" to idle.copy(retiring = true),
        "download id residue" to idle.copy(downloadId = downloadId),
        "pending path" to idle.copy(pendingRelativePath = "pending%2F.bin"),
        "pending size" to idle.copy(pendingSizeBytes = 7L),
        "pending path ownership" to idle.copy(ownsPendingPath = true),
        "retired path" to idle.copy(retiredRelativePath = "retired%2F.cbz"),
        "conversion roster residue" to idle.copy(conversionSourceRoster = roster),
        "active conversion" to idle.copy(
            token = "captured-convert", operation = ChapterArtifactOperation.CONVERT,
            downloadId = downloadId, conversionSourceRoster = roster, committedRelativePath = null,
        ),
        "mismatched manga" to idle.copy(mangaId = otherMangaId),
        "mismatched chapter URL" to idle.copy(chapterUrl = "$STRICT_OLD/different-chapter"),
        "committed path without token" to idle.copy(committedToken = null),
    )

/** IGNORE is reachable only after earlier saved/artifact URL writes really happened in this writer. */
private fun ignoreSecondArtifactAfterFirstMoved(
    first: StrictSavedFamily,
    second: StrictSavedFamily,
): String {
    val firstWork = STRICT_NEW + first.work.url.removePrefix(STRICT_OLD)
    val firstChapter = STRICT_NEW + first.chapter.url.removePrefix(STRICT_OLD)
    val secondChapter = STRICT_NEW + second.chapter.url.removePrefix(STRICT_OLD)
    return """
    CREATE TRIGGER strict_artifact_late_ignore BEFORE UPDATE OF chapterUrl ON chapter_artifacts
    WHEN OLD.chapterId = ${second.chapter.id} BEGIN
      SELECT CASE WHEN
        (SELECT url FROM saved_manga WHERE id = ${first.work.id}) = '$firstWork'
        AND (SELECT url FROM saved_chapters WHERE id = ${first.chapter.id}) = '$firstChapter'
        AND (SELECT chapterUrl FROM chapter_artifacts WHERE chapterId = ${first.chapter.id}) = '$firstChapter'
        AND (SELECT url FROM saved_chapters WHERE id = ${second.chapter.id}) = '$secondChapter'
      THEN RAISE(IGNORE) ELSE RAISE(ABORT, 'earlier URL writes did not occur') END;
    END
    """.trimIndent()
}
