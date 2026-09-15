package me.manga.kira.platform.download

import kotlinx.cinterop.ExperimentalForeignApi
import me.manga.kira.platform.filesystem.AppFileSystem
import me.manga.kira.platform.media.PageBytePolicy
import me.manga.kira.platform.media.PageMediaInspector
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
    private val stagingDirectory = appFileSystem.filesDir / ".download-staging"
    private val stagingPreparation by lazy(LazyThreadSafetyMode.SYNCHRONIZED) { pruneAbandonedStaging() }

    /** The singleton transport calls this before attaching its first native session. Never rescan. */
    fun prepareStaging() {
        stagingPreparation
    }

    fun beginPublication(): Publication {
        // Native handler tests can deliver callbacks without creating a background session. Share
        // the same synchronized one-shot gate so a later session touch cannot erase a live handoff.
        prepareStaging()
        return Publication(appFileSystem.fileSystem())
    }

    private fun pruneAbandonedStaging() {
        val system = appFileSystem.fileSystem()
        var failures = 0
        try {
            val directory = system.metadataOrNull(stagingDirectory) ?: return
            if (!directory.isDirectory || directory.symlinkTarget != null) return
            for (path in system.list(stagingDirectory)) {
                if (path.parent != stagingDirectory || !isStagingName(path.name)) continue
                try {
                    val metadata = system.metadataOrNull(path) ?: continue
                    if (metadata.isRegularFile && metadata.symlinkTarget == null) {
                        system.delete(path, mustExist = false)
                    }
                } catch (_: IOException) {
                    failures++
                }
            }
        } catch (_: IOException) {
            failures++
        }
        // An unreadable/undeletable file remains for a later process startup, never a live sweep.
        if (failures > 0) BgDownloadLog.warn("files.partialDeleteFailed", "count" to failures)
    }

    private fun isStagingName(name: String): Boolean {
        val match = STAGING_NAME.matchEntire(name) ?: return false
        return match.groupValues[1].toIntOrNull() != null
    }

    /** The callback reports failure before its finally discards this publication's temporary. */
    inner class Publication(
        private val system: FileSystem,
    ) {
        private var ownedTemporary: Path? = null

        fun stage(
            location: NSURL,
            d: IosTransferIdentity,
            declaredLength: Long?,
        ): StagedDownloadPage {
            pageBytePolicy.checkDeclaredLength(declaredLength)
            val temporary = retainDownloadedPage(location, d, system)
            ownedTemporary = temporary
            requireRegularPage(system, temporary, "Retained page is not a regular file")
            val metadata = mediaInspector.inspect(temporary).requireValid()
            return StagedDownloadPage(system, temporary, metadata)
        }

        fun handOff() {
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
        val directory = stagingDirectory
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

    private companion object {
        val STAGING_NAME = Regex(
            "\\.image_(0|[1-9][0-9]{0,9})-[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-" +
                "[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}\\.partial",
        )
    }
}
