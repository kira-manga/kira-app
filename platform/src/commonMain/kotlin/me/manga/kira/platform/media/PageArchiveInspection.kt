package me.manga.kira.platform.media

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import okio.FileSystem
import okio.IOException
import okio.Path
import okio.Path.Companion.toPath
import okio.openZip
import okio.use

/**
 * Returns the complete readable image-entry count, or fails on ANY invalid/read/byte-policy result.
 * This is not proof of the original source roster when a legacy CBZ has no input/split manifest.
 */
suspend fun inspectPageArchive(
    system: FileSystem,
    archive: Path,
    inspector: PageMediaInspector,
    policy: PageBytePolicy = PageBytePolicy(),
): Int = visitValidatedArchivePages(system, archive, inspector, policy) { _, _, _ -> }

internal suspend fun visitValidatedArchivePages(
    system: FileSystem,
    archive: Path,
    inspector: PageMediaInspector,
    policy: PageBytePolicy,
    visit: (Path, ByteArray, PageImageMetadata) -> Unit,
): Int = system.openZip(archive).use { zip ->
    val entries = zip.listRecursively(ZIP_ROOT)
        .filter { zip.metadata(it).isRegularFile && isPageImageName(it.name) }
        .sortedBy { it.toString() }
        .toList()
    if (entries.isEmpty()) throw IOException("Archive contains no readable image pages")
    for (entry in entries) {
        currentCoroutineContext().ensureActive()
        val bytes = readPageSnapshot(zip, entry, policy)
        val metadata = inspector.inspect(bytes).requireValid()
        currentCoroutineContext().ensureActive()
        visit(entry, bytes, metadata)
    }
    entries.size
}

/** Candidate selection only. Matching a filename never makes its bytes a validated page. */
fun isPageImageName(name: String): Boolean = name.substringAfterLast('.', "").lowercase() in PAGE_IMAGE_EXTENSIONS

private val ZIP_ROOT = "/".toPath()
private val PAGE_IMAGE_EXTENSIONS = PageImageFormat.entries.map { it.extension }.toSet() + "jpeg"
