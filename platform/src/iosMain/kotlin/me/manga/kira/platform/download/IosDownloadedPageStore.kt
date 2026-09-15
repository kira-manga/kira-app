package me.manga.kira.platform.download

import kotlinx.cinterop.ExperimentalForeignApi
import me.manga.kira.platform.filesystem.AppFileSystem
import me.manga.kira.platform.filesystem.chapterDir
import me.manga.kira.platform.media.PageBytePolicy
import me.manga.kira.platform.media.PageMediaInspector
import me.manga.kira.platform.media.publishPageSnapshot
import me.manga.kira.platform.media.requireValid
import okio.FileSystem
import okio.IOException
import okio.Path
import okio.Path.Companion.toPath
import platform.Foundation.NSFileManager
import platform.Foundation.NSURL
import platform.Foundation.NSUUID

/** Adopts the callback-local OS file and owns only that retained snapshot until publication. */
@OptIn(ExperimentalForeignApi::class)
internal class IosDownloadedPageStore(
    private val appFileSystem: AppFileSystem,
    private val mediaInspector: PageMediaInspector,
    private val pageBytePolicy: PageBytePolicy,
) {
    fun beginPublication(): Publication = Publication(appFileSystem.fileSystem())

    /** The callback reports failure before its finally discards this publication's temporary. */
    inner class Publication(
        private val system: FileSystem,
    ) {
        private var ownedTemporary: Path? = null

        fun publish(
            location: NSURL,
            d: IosTransferIdentity,
            declaredLength: Long?,
        ) {
            pageBytePolicy.checkDeclaredLength(declaredLength)
            val temporary = retainDownloadedPage(location, d, system)
            ownedTemporary = temporary
            requireRegularPage(system, temporary, "Retained page is not a regular file")
            val metadata = mediaInspector.inspect(temporary).requireValid()
            publishPageSnapshot(system, temporary, d.pageIndex, metadata)
            ownedTemporary = null
        }

        fun discardTemporary() {
            ownedTemporary?.let { this@IosDownloadedPageStore.discardTemporary(system, it) }
        }
    }

    private fun retainDownloadedPage(
        location: NSURL,
        d: IosTransferIdentity,
        system: FileSystem,
    ): Path {
        val locationPath = location.path?.toPath() ?: throw IOException("Missing downloaded file")
        requireRegularPage(system, locationPath, "Downloaded page is not a regular file")
        val directory = appFileSystem.chapterDir(d.mangaId, d.chapterId)
        system.createDirectories(directory)
        val temporary = directory / ".image_${d.pageIndex}-${NSUUID().UUIDString}.partial"
        // moveItem, unlike atomicMove, refuses an existing destination. Ownership begins only
        // after it returns, before URLSession removes the callback-local file.
        val retained =
            NSFileManager.defaultManager.moveItemAtURL(
                location,
                NSURL.fileURLWithPath(temporary.toString()),
                error = null,
            )
        if (!retained) throw IOException("Could not retain downloaded page")
        return temporary
    }

    private fun requireRegularPage(
        system: FileSystem,
        path: Path,
        message: String,
    ) {
        val metadata = system.metadata(path)
        if (!metadata.isRegularFile) throw IOException(message)
        pageBytePolicy.checkFileSize(metadata.size)
    }

    private fun discardTemporary(
        system: FileSystem,
        path: Path,
    ) {
        try {
            system.delete(path, mustExist = false)
        } catch (_: IOException) {
            // No published page was removed.
        }
    }
}
