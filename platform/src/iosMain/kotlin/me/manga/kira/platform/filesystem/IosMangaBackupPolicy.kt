package me.manga.kira.platform.filesystem

import co.touchlab.kermit.Logger
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ObjCObjectVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.value
import okio.FileSystem
import okio.IOException
import okio.Path
import platform.Foundation.NSError
import platform.Foundation.NSNumber
import platform.Foundation.NSURL
import platform.Foundation.NSURLIsExcludedFromBackupKey
import platform.Foundation.numberWithBool

private val backupLog = Logger.withTag("IosAppFileSystem")

/**
 * Exclude the stable chapter-media parent, including existing files and future child publications.
 * Never exclude Documents itself: it also contains user-authored Room state. No success marker is
 * persisted, so a lost flag or a failed attempt is retried on the next filesystem initialization.
 */
@OptIn(BetaInteropApi::class, ExperimentalForeignApi::class)
internal fun prepareIosMangaBackupExclusion(documentsDir: Path) {
    val mangaRoot = documentsDir / "manga"
    try {
        FileSystem.SYSTEM.createDirectories(mangaRoot)
        val metadata = FileSystem.SYSTEM.metadata(mangaRoot)
        if (!metadata.isDirectory || metadata.symlinkTarget != null) {
            backupLog.e { "backup.manga.exclude.failed reason=not-directory retry=next-launch" }
            return
        }
    } catch (error: IOException) {
        backupLog.e {
            "backup.manga.exclude.failed reason=directory-io type=${error::class.simpleName} retry=next-launch"
        }
        return
    }

    memScoped {
        val nativeError = alloc<ObjCObjectVar<NSError?>>()
        nativeError.value = null
        val excluded =
            NSURL.fileURLWithPath(mangaRoot.toString()).setResourceValue(
                value = NSNumber.numberWithBool(true),
                forKey = NSURLIsExcludedFromBackupKey,
                error = nativeError.ptr,
            )
        if (!excluded) {
            val error = nativeError.value
            backupLog.e {
                "backup.manga.exclude.failed reason=resource-value domain=${error?.domain} " +
                    "code=${error?.code} retry=next-launch"
            }
        }
    }
}
