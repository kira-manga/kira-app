package me.manga.kira.data.repository

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import me.manga.kira.core.util.runCatchingCancellable
import me.manga.kira.data.download.artifacts.ChapterArtifactReference
import me.manga.kira.data.local.dao.ArtifactRepairSnapshot
import me.manga.kira.data.local.entity.ChapterArtifactOwner
import me.manga.kira.platform.filesystem.AppFileSystem
import me.manga.kira.platform.filesystem.chapterDir
import okio.Path
import okio.Path.Companion.toPath

/**
 * Negative evidence only, not a media validator. Existing, corrupt, inaccessible or ambiguous
 * bytes are retained. Never turn a decoder failure or a failed filesystem call into absence.
 */
internal class MissingDownloadedFiles(private val files: AppFileSystem) {
    suspend fun provenMissing(snapshot: ArtifactRepairSnapshot): Boolean = runCatchingCancellable {
        val owner = ChapterArtifactOwner.of(snapshot.chapter)
        val directory = files.chapterDir(owner.mangaId, owner.chapterId)
        snapshot.artifact?.committedRelativePath?.let { relative ->
            val exact = ChapterArtifactReference.resolve(files, owner, relative)
            // An explicit generation must not fall back to an older canonical CBZ.
            return@runCatchingCancellable presence(exact, files.filesDir) == Presence.ABSENT
        }
        if (presence(directory / "chapter_${owner.chapterId}.cbz", files.filesDir) != Presence.ABSENT) {
            return@runCatchingCancellable false
        }
        val stored = snapshot.chapter.localImagePaths
        if (stored.isEmpty()) {
            // Empty paths alone are not proof: an interrupted full deletion may retain its files.
            return@runCatchingCancellable presence(directory, files.filesDir) == Presence.ABSENT
        }
        if (stored.singleOrNull()?.endsWith(".cbz", ignoreCase = true) == true) {
            return@runCatchingCancellable storedPresence(stored.single(), directory) == Presence.ABSENT
        }
        missingRoster(stored, directory)
    }.getOrDefault(false)

    private suspend fun missingRoster(stored: List<String>, directory: Path): Boolean {
        var missing = false
        for (raw in stored) {
            currentCoroutineContext().ensureActive()
            val path = parse(raw) ?: return false
            val current = presence(directory / path.name, files.filesDir)
            val page = if (current == Presence.PRESENT) current else {
                alternatives(current, storedPresence(raw, directory))
            }
            if (page == Presence.UNKNOWN) return false
            if (page == Presence.ABSENT) missing = true
        }
        return missing
    }

    private fun storedPresence(raw: String, directory: Path): Presence {
        val stored = parse(raw) ?: return Presence.UNKNOWN
        if (stored.parent == directory) return presence(stored, files.filesDir)
        // Only the known iOS container UUID may move. Never use an unrelated absolute path,
        // another chapter's file, or a guessed basename as negative authority over this owner.
        val priorRoot = stored.parent?.parent?.parent?.parent ?: return Presence.UNKNOWN
        val mangaDirectory = directory.parent ?: return Presence.UNKNOWN
        if (stored.parent != priorRoot / "manga" / mangaDirectory.name / directory.name ||
            !sameIosDocumentsLocation(files.filesDir, priorRoot)
        ) return Presence.UNKNOWN
        return presence(stored, priorRoot.parent ?: return Presence.UNKNOWN)
    }

    private fun parse(raw: String): Path? {
        if (raw.isBlank()) return null
        val path = raw.toPath()
        return path.takeIf { it.isAbsolute && it.name.isNotBlank() && it.segments.none { part -> part == ".." || part == "." } }
    }

    private fun presence(path: Path, root: Path): Presence {
        val relative = path.relativeTo(root)
        if (relative.segments.any { it == ".." }) return Presence.UNKNOWN
        var current = root
        for (part in listOf<String?>(null) + relative.segments) {
            if (part != null) current /= part
            val metadata = files.fileSystem().metadataOrNull(current) ?: return Presence.ABSENT
            if (metadata.symlinkTarget != null || (current != path && !metadata.isDirectory)) return Presence.UNKNOWN
        }
        return Presence.PRESENT
    }
}

private enum class Presence { ABSENT, PRESENT, UNKNOWN }

private fun alternatives(first: Presence, second: Presence): Presence = when {
    first == Presence.PRESENT || second == Presence.PRESENT -> Presence.PRESENT
    first == Presence.UNKNOWN || second == Presence.UNKNOWN -> Presence.UNKNOWN
    else -> Presence.ABSENT
}

private fun sameIosDocumentsLocation(current: Path, prior: Path): Boolean {
    fun containerParent(path: Path): List<String>? {
        val parts = path.segments.let {
            if (it.take(2) == listOf("private", "var")) it.drop(1) else it
        }
        if (parts.lastOrNull() != "Documents" || !CONTAINER_UUID.matches(parts.getOrNull(parts.lastIndex - 1).orEmpty())) return null
        return parts.dropLast(2).takeIf { it.takeLast(3) == listOf("Containers", "Data", "Application") }
    }
    val currentParent = containerParent(current) ?: return false
    return currentParent == containerParent(prior)
}

private val CONTAINER_UUID = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}", RegexOption.IGNORE_CASE)
