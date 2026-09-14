package me.manga.kira.platform.media

import okio.FileSystem
import okio.IOException
import okio.Path

/**
 * Publish the SAME validated snapshot. Atomic replacement is the first operation affecting a good
 * existing page; alternate extensions are removed only after it succeeds. Never copy/rewrite here.
 */
fun publishPageSnapshot(system: FileSystem, temporary: Path, index: Int, metadata: PageImageMetadata): Path {
    require(index >= 0)
    val directory = requireNotNull(temporary.parent)
    val target = directory / "image_$index.${metadata.format.extension}"
    require(temporary != target)
    system.atomicMove(temporary, target)
    try {
        system.list(directory).filter { candidate ->
            candidate != target && candidate.name.startsWith("image_$index.") && isPageImageName(candidate.name)
        }.forEach { old -> system.delete(old, mustExist = false) }
    } catch (_: IOException) {
        // A failed best-effort alternate cleanup cannot turn an already-committed page into failure.
        // Reconciliation chooses the validated manifest-bound index, never counts duplicates twice.
    }
    return target
}
