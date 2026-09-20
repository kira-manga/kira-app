package me.manga.kira.data.download.artifacts

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import me.manga.kira.data.local.dao.ArtifactRepairSnapshot
import me.manga.kira.data.local.entity.ChapterArtifactOwner
import me.manga.kira.platform.filesystem.AppFileSystem
import me.manga.kira.platform.filesystem.chapterDir
import me.manga.kira.presentation.features.download.domain.clean.DownloadManifest
import me.manga.kira.presentation.features.download.domain.clean.PageFileNames
import okio.Path

/** Absence proof only. Invalid/ambiguous existing bytes never grant permission to replace them. */
internal class RestoredDownloadFiles(private val files: AppFileSystem) {
    private val missing = MissingDownloadedFiles(files)

    suspend fun inspect(snapshot: ArtifactRepairSnapshot, manifest: DownloadManifest?): RestoredDownloadMedia {
        val owner = ChapterArtifactOwner.of(snapshot.chapter)
        val directory = files.chapterDir(owner.mangaId, owner.chapterId)
        val roster = inspectRoster(directory, manifest) ?: return RestoredDownloadMedia.Unproven
        if (manifest != null && roster.isEmpty()) return RestoredDownloadMedia.CompleteRoster
        val committed = snapshot.artifact?.committedRelativePath
        if (committed != null && presence(ChapterArtifactReference.resolve(files, owner, committed)) != Presence.ABSENT) {
            return RestoredDownloadMedia.Unproven
        }
        when (presence(directory / "chapter_${owner.chapterId}.cbz")) {
            Presence.PRESENT -> return if (committed == null) RestoredDownloadMedia.CanonicalArchive else RestoredDownloadMedia.Unproven
            Presence.UNKNOWN -> return RestoredDownloadMedia.Unproven
            Presence.ABSENT -> Unit
        }
        if (snapshot.chapter.localImagePaths.isNotEmpty() && !missing.provenMissing(snapshot.copy(artifact = null))) {
            return RestoredDownloadMedia.Unproven // A complete saved copy or unknown prior-container path survives.
        }
        if (manifest == null && snapshot.chapter.localImagePaths.isEmpty() && !emptyDirectory(directory)) {
            return RestoredDownloadMedia.Unproven // Without a roster, surviving bytes might still be a complete copy.
        }
        return RestoredDownloadMedia.Missing(roster, manifestMissing = manifest == null)
    }

    /** A missing index has NO named candidate; decoder errors and duplicate/symlink candidates are not absence. */
    private suspend fun inspectRoster(directory: Path, manifest: DownloadManifest?): Set<Int>? {
        if (presence(directory / "manifest.json") == Presence.UNKNOWN) return null
        val contents = when (presence(directory)) {
            Presence.ABSENT -> emptyList()
            Presence.UNKNOWN -> return null
            Presence.PRESENT -> {
                if (!files.fileSystem().metadata(directory).isDirectory) return null
                files.fileSystem().list(directory)
            }
        }
        val expected = manifest?.pages?.map { it.index }?.toSet().orEmpty()
        val present = mutableSetOf<Int>()
        for (path in contents) {
            currentCoroutineContext().ensureActive()
            val index = PageFileNames.pageIndexFromName(path.name)?.takeIf { it in expected } ?: continue
            val metadata = files.fileSystem().metadataOrNull(path) ?: continue
            if (!metadata.isRegularFile || metadata.symlinkTarget != null || !present.add(index)) return null
        }
        return expected - present
    }

    private fun emptyDirectory(directory: Path): Boolean = when (presence(directory)) {
        Presence.ABSENT -> true
        Presence.UNKNOWN -> false
        Presence.PRESENT -> files.fileSystem().metadata(directory).isDirectory && files.fileSystem().list(directory).isEmpty()
    }

    private fun presence(path: Path): Presence = missing.presence(path, files.filesDir)
}

internal sealed interface RestoredDownloadMedia {
    data object CompleteRoster : RestoredDownloadMedia
    data object CanonicalArchive : RestoredDownloadMedia
    data class Missing(val pages: Set<Int>, val manifestMissing: Boolean) : RestoredDownloadMedia
    data object Unproven : RestoredDownloadMedia
}
