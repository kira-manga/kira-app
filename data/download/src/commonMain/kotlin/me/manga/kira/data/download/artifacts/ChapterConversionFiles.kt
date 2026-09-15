package me.manga.kira.data.download.artifacts

import me.manga.kira.data.local.entity.ChapterArtifactClaim
import me.manga.kira.data.local.entity.ChapterArtifactOwner
import me.manga.kira.data.local.entity.ChapterConversionRoster
import me.manga.kira.data.local.entity.ChapterConversionSource
import me.manga.kira.data.local.entity.SavedChapterEntity
import me.manga.kira.platform.filesystem.AppFileSystem
import me.manga.kira.platform.filesystem.chapterDir
import me.manga.kira.platform.media.PageMediaInspector
import me.manga.kira.platform.media.inspectPageArchive
import me.manga.kira.platform.media.isPageImageName
import okio.FileMetadata
import okio.Path
import okio.Path.Companion.toPath

/** CONVERT's file boundary, always used under the existing chapter pin, never a separate owner. */
internal class ChapterConversionFiles(
    private val files: AppFileSystem,
    private val inspector: PageMediaInspector,
) {
    private val fs get() = files.fileSystem()

    fun capture(chapter: SavedChapterEntity): String {
        val owner = ChapterArtifactOwner.of(chapter)
        val sources = chapter.localImagePaths.map { stored ->
            ChapterConversionSource(stored, source(owner, stored).name)
        }
        return ChapterConversionRoster.encode(sources)
    }

    /** All-stale legacy fallback is the ONLY no-writer adoption; one missing input is not enough. */
    suspend fun prepare(claim: ChapterArtifactClaim, write: suspend (List<Path>) -> Path): ConvertedChapterFile {
        val sources = sources(claim)
        val canonical = canonical(claim.owner)
        if (!sources.all { fs.metadataOrNull(it) == null }) {
            check(write(sources) == canonical) { "Conversion returned a noncanonical archive" }
        }
        return validateArchive(claim)
    }

    fun canonical(owner: ChapterArtifactOwner): Path = directory(owner) / "chapter_${owner.chapterId}.cbz"

    fun archiveMetadata(claim: ChapterArtifactClaim): FileMetadata? = metadata(canonical(claim.owner))

    suspend fun validateArchive(claim: ChapterArtifactClaim): ConvertedChapterFile {
        val archive = canonical(claim.owner)
        val size = checkNotNull(metadata(archive)?.size)
        check(size > 0)
        inspectPageArchive(fs, archive, inspector)
        return ConvertedChapterFile(archive, size)
    }

    /** Missing entries are already cleaned. No recursive deletion or directory/file-name sweep. */
    fun cleanSources(claim: ChapterArtifactClaim) {
        val paths = sources(claim) // Validate the entire captured roster before deleting anything.
        paths.forEach { path ->
            directory(claim.owner)
            if (metadata(path) != null) fs.delete(path, mustExist = false)
        }
    }

    /** Recheck the persisted payload, not a replacement metadata snapshot or a directory listing. */
    fun sources(claim: ChapterArtifactClaim): List<Path> =
        ChapterConversionRoster.decode(checkNotNull(claim.conversionSourceRoster)).map { entry ->
            source(claim.owner, entry.storedPath).also { check(it.name == entry.relativePath) }
        }

    private fun source(owner: ChapterArtifactOwner, stored: String): Path {
        val original = stored.toPath()
        require(original.segments.none { it == ".." || it == "." } && '\u0000' !in stored && '\\' !in stored)
        val name = original.name
        require(isPageImageName(name) && !name.startsWith('.'))
        val current = directory(owner) / name
        val suffix = "manga/${owner.mangaId}/chapter_${owner.chapterId}/$name"
        require(original == current || stored == suffix || stored.endsWith("/$suffix"))
        // Match the writer's current-container resolution without granting an external old path
        // deletion authority. An extant external alias/copy is not ours to convert or reclaim.
        if (original != current) require(fs.metadataOrNull(original) == null)
        metadata(current)
        return current
    }

    /** Reject symlink escapes at every untrusted component below the configured application root. */
    private fun directory(owner: ChapterArtifactOwner): Path {
        val root = files.filesDir
        check(fs.metadata(root).isDirectory)
        var expected = fs.canonicalize(root)
        var path = root
        for (part in listOf("manga", owner.mangaId.toString(), "chapter_${owner.chapterId}")) {
            path /= part
            expected /= part
            fs.metadataOrNull(path)?.let { meta ->
                check(meta.isDirectory && meta.symlinkTarget == null && fs.canonicalize(path) == expected)
            }
        }
        check(path == files.chapterDir(owner.mangaId, owner.chapterId))
        return path
    }

    private fun metadata(path: Path): FileMetadata? = fs.metadataOrNull(path)?.also { meta ->
        check(meta.isRegularFile && meta.symlinkTarget == null)
        check(fs.canonicalize(path) == fs.canonicalize(checkNotNull(path.parent)) / path.name)
    }
}

internal data class ConvertedChapterFile(val path: Path, val sizeBytes: Long)
