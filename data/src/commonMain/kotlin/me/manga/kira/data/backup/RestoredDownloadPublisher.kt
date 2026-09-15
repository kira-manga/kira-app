package me.manga.kira.data.backup

import co.touchlab.kermit.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import me.manga.kira.data.download.artifacts.ChapterArtifactRecovery
import me.manga.kira.data.download.artifacts.ChapterArtifactReference
import me.manga.kira.data.download.artifacts.ChapterArtifacts
import me.manga.kira.data.local.dao.ChapterArtifactCommitDao
import me.manga.kira.data.local.dao.ChapterArtifactDao
import me.manga.kira.data.local.dao.ChapterRestoreOutcome
import me.manga.kira.data.local.entity.ChapterDownloadEntity
import me.manga.kira.data.local.entity.SavedChapterEntity
import me.manga.kira.platform.filesystem.AppFileSystem
import me.manga.kira.presentation.features.download.data.DownloadingState
import okio.Buffer
import okio.IOException
import okio.Path
import okio.buffer

/** App9's privately owned, immutable, fully validated source; its owner must stay open throughout. */
data class RestoredChapterArchive(val path: Path, val sizeBytes: Long)

/** Publishes an already validated CBZ, never ZIP/JSON parsing or receiver identity resolution. */
class RestoredDownloadPublisher(
    private val artifacts: ChapterArtifacts,
    private val dao: ChapterArtifactDao,
    private val commits: ChapterArtifactCommitDao,
    private val appFileSystem: AppFileSystem,
    private val recovery: ChapterArtifactRecovery,
) {
    private val log = Logger.withTag("RestoredDownloadPublisher")

    suspend fun publish(
        archive: RestoredChapterArchive,
        expected: SavedChapterEntity,
        api: String,
        mangaTitle: String,
    ): ChapterRestoreOutcome {
        val claim = artifacts.beginRestore(expected, archive.sizeBytes) ?: return ChapterRestoreOutcome.NOT_COMMITTED
        var created = false
        var outcome = ChapterRestoreOutcome.UNKNOWN
        try {
            artifacts.files(claim) {
                val target = ChapterArtifactReference.resolve(appFileSystem, claim.owner, checkNotNull(claim.relativePath))
                createGeneration(target)
                created = true
                check(artifacts.publish(claim) { dao.confirmPendingPathOwnership(expected.id, claim.token) } == 1)
                copyArchive(archive, target)
                artifacts.publish(claim) { commits.commitRestore(claim, expected, target.toString(), terminalRow(expected, api, mangaTitle, archive)) }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            log.w { "Restore publication needs settlement (${failure::class.simpleName})" }
        } finally {
            outcome = withContext(NonCancellable) { recovery.settleRestore(artifacts, claim, created) }
        }
        return outcome
    }

    private fun createGeneration(target: Path) {
        val directory = checkNotNull(target.parent)
        val parent = checkNotNull(directory.parent)
        val fs = appFileSystem.fileSystem()
        fs.createDirectories(parent)
        // Random naming is not proof: exclusive creation must succeed before any byte is copied.
        fs.createDirectory(directory, mustCreate = true)
    }

    private suspend fun copyArchive(archive: RestoredChapterArchive, target: Path) {
        val fs = appFileSystem.fileSystem()
        val metadata = fs.metadata(archive.path)
        if (!metadata.isRegularFile || metadata.size != archive.sizeBytes) throw IOException("Validated archive changed")
        val part = checkNotNull(target.parent) / "${target.name}.part"
        fs.sink(part, mustCreate = true).buffer().use { sink ->
            fs.source(archive.path).buffer().use { source ->
                val buffer = Buffer()
                var remaining = archive.sizeBytes
                while (remaining > 0) {
                    currentCoroutineContext().ensureActive()
                    val read = source.read(buffer, minOf(COPY_BUFFER_BYTES, remaining))
                    if (read <= 0) throw IOException("Validated archive copy made no progress")
                    sink.write(buffer, read)
                    remaining -= read
                }
                if (source.read(buffer, 1) != -1L) throw IOException("Validated archive grew")
            }
        }
        if (fs.metadata(part).size != archive.sizeBytes) throw IOException("Incomplete archive copy")
        fs.atomicMove(part, target)
    }

    private fun terminalRow(
        expected: SavedChapterEntity,
        api: String,
        title: String,
        archive: RestoredChapterArchive,
    ): ChapterDownloadEntity = ChapterDownloadEntity(
        number = expected.number, chapterId = expected.id, mangaId = expected.mangaId, api = api,
        mangaTitle = title, url = expected.url, state = DownloadingState.SUCCESS, progress = 100,
        sizeBytes = archive.sizeBytes,
    )

    private companion object {
        const val COPY_BUFFER_BYTES = 64L * 1024
    }
}
