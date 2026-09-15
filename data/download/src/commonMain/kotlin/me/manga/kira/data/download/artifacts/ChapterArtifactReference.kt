package me.manga.kira.data.download.artifacts

import me.manga.kira.data.local.entity.ChapterArtifactOwner
import me.manga.kira.platform.filesystem.AppFileSystem
import me.manga.kira.platform.filesystem.chapterDir
import okio.Path

/** Full relative restored references survive sandbox-root changes without canonical fallback. */
object ChapterArtifactReference {
    private const val DIRECTORY = "_restored"
    private const val ARCHIVE = "chapter.cbz"
    private val tokenPattern = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")

    fun restored(token: String): String {
        require(tokenPattern.matches(token))
        return "$DIRECTORY/$token/$ARCHIVE"
    }

    /** Accept only app-generated generations, never arbitrary archive paths or parent traversal. */
    fun resolve(fileSystem: AppFileSystem, owner: ChapterArtifactOwner, relativePath: String): Path {
        val parts = relativePath.split('/')
        require(parts.size == 3 && parts[0] == DIRECTORY && parts[2] == ARCHIVE)
        require(tokenPattern.matches(parts[1]))
        return fileSystem.chapterDir(owner.mangaId, owner.chapterId) / relativePath
    }
}
