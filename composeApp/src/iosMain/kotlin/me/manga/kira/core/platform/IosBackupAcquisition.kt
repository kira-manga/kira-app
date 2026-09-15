@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package me.manga.kira.core.platform

import kotlinx.cinterop.ObjCObjectVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.value
import me.manga.kira.platform.backup.BackupImportStaging
import me.manga.kira.platform.backup.InvalidBackupArchive
import okio.FileSystem
import okio.IOException
import okio.Path.Companion.toPath
import platform.Foundation.NSError
import platform.Foundation.NSFileCoordinator
import platform.Foundation.NSURL

/**
 * Copies an open-in-place document while its security scope and coordinated read are held. Only
 * this application's stream copy is bounded; provider/OS hydration before the accessor is not.
 * No Kotlin exception may unwind through Foundation's Objective-C accessor block.
 */
internal fun acquireIosBackup(
    url: NSURL,
    staging: BackupImportStaging,
    checkpoint: () -> Unit,
): String {
    checkpoint()
    val scoped = url.startAccessingSecurityScopedResource()
    try {
        return coordinateBackupSnapshot(url, staging, checkpoint)
    } finally {
        if (scoped) url.stopAccessingSecurityScopedResource()
    }
}

private fun coordinateBackupSnapshot(
    url: NSURL,
    staging: BackupImportStaging,
    checkpoint: () -> Unit,
): String = memScoped {
    val error = alloc<ObjCObjectVar<NSError?>>()
    error.value = null
    val access = CoordinatedBackupAccess(staging, checkpoint)
    try {
        NSFileCoordinator(filePresenter = null).coordinateReadingItemAtURL(
            url,
            options = 0uL,
            error = error.ptr,
            byAccessor = access::read,
        )
        access.failure?.let { throw it }
        if (error.value != null) throw IOException("Backup document coordination failed")
        checkpoint()
        access.stagedPath ?: throw IOException("Backup document was not made available")
    } catch (failure: Throwable) {
        access.discardAfterFailure(failure)
        throw failure
    }
}

private class CoordinatedBackupAccess(
    private val staging: BackupImportStaging,
    private val checkpoint: () -> Unit,
) {
    var stagedPath: String? = null
        private set
    var failure: Throwable? = null
        private set
    private var called = false

    fun read(url: NSURL?) {
        try {
            if (called) throw InvalidBackupArchive()
            called = true
            checkpoint()
            val path = url?.path?.toPath() ?: throw IOException("Backup document has no local file")
            val system = FileSystem.SYSTEM
            val metadata = system.metadata(path)
            if (!metadata.isRegularFile) throw InvalidBackupArchive()
            stagedPath = staging.stage(metadata.size, checkpoint) { system.source(path) }
        } catch (caught: Throwable) {
            failure = caught
        }
    }

    fun discardAfterFailure(original: Throwable) {
        val owned = stagedPath ?: return
        try {
            staging.discardPending(owned)
        } catch (cleanup: Throwable) {
            if (cleanup !== original) original.addSuppressed(cleanup)
        }
    }
}
