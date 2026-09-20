package me.manga.kira.platform.storage

import android.content.Context
import android.os.Process
import android.os.UserManager
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.system.StructStat
import androidx.core.util.AtomicFile
import kotlinx.coroutines.CancellationException
import java.io.File
import java.io.FileDescriptor
import java.io.FileOutputStream

internal enum class AndroidCredentialSlot(
    val fileName: String,
    val byteLimit: Int,
) {
    CREDENTIAL("credential.bin", AndroidCredentialEnvelope.MAX_BYTES),
    MARKER("cleanup-marker.json", CredentialCleanupMarker.MAX_ENCODED_BYTES),
    PENDING_ACTIONS("pending-actions.bin", PendingComplaintSnapshot.MAX_ANDROID_PHYSICAL_BYTES),
}

internal enum class AndroidCredentialVariant(
    val suffix: String,
) {
    BASE(""),
    NEW(".new"),
    BACKUP(".bak"),
}

internal class AndroidCredentialPieces(
    val base: Boolean,
    val pending: Boolean,
    val backup: Boolean,
) {
    val absent: Boolean get() = !base && !pending && !backup
}

/** Only fixed file primitives. Absence means ENOENT, never File.exists() swallowing an I/O error. */
internal interface AndroidCredentialFileIo {
    fun prepareDirectory()

    fun exists(
        slot: AndroidCredentialSlot,
        variant: AndroidCredentialVariant,
    ): Boolean

    fun readBounded(
        slot: AndroidCredentialSlot,
        variant: AndroidCredentialVariant,
        limit: Int,
    ): ByteArray?

    fun syncFile(slot: AndroidCredentialSlot)

    fun syncDirectory()

    fun startWrite(slot: AndroidCredentialSlot): AndroidCredentialWrite

    fun remove(
        slot: AndroidCredentialSlot,
        variant: AndroidCredentialVariant,
    )
}

internal interface AndroidCredentialWrite {
    fun write(bytes: ByteArray)

    fun sync()

    fun finish()

    fun rollback()

    fun isClosed(): Boolean

    fun close()
}

/**
 * AndroidX Core 1.19 AtomicFile writes .new but suppresses sync/close/rename/delete failures.
 * This protocol independently checks them, never invokes its unbounded read or legacy-backup recovery,
 * and never rolls back after finish was attempted. Native restart/reboot durability needs device QA.
 */
internal class AndroidCredentialAtomicFile(
    private val io: AndroidCredentialFileIo,
    private val checkActive: () -> Unit,
) {
    private val state = AndroidCredentialFileState(io)
    private val transaction = AndroidCredentialAtomicTransaction(io, state, checkActive, this)

    fun prepare() = checked { io.prepareDirectory() }

    fun pieces(slot: AndroidCredentialSlot): AndroidCredentialPieces = checked { state.pieces(slot) }

    fun read(
        slot: AndroidCredentialSlot,
        variant: AndroidCredentialVariant,
    ): ByteArray? = checked { state.read(slot, variant) }

    fun proveAbsent(slot: AndroidCredentialSlot) {
        if (!pieces(slot).absent) androidCredentialPermanent(InstallationPermanentFailure.READ_BACK_MISMATCH)
        checked { io.syncDirectory() }
        if (!pieces(slot).absent) androidCredentialPermanent(InstallationPermanentFailure.READ_BACK_MISMATCH)
    }

    fun confirm(
        slot: AndroidCredentialSlot,
        expected: ByteArray,
    ) {
        checked { state.verify(slot, expected) }
        checked { io.syncFile(slot) }
        checked { io.syncDirectory() }
        checked { state.verify(slot, expected) }
    }

    fun discardPending(
        slot: AndroidCredentialSlot,
        validatedBase: ByteArray,
    ) {
        val before = pieces(slot)
        if (!before.base || before.backup || !sameBase(slot, validatedBase)) {
            androidCredentialPermanent(InstallationPermanentFailure.READ_BACK_MISMATCH)
        }
        if (before.pending) checked { io.remove(slot, AndroidCredentialVariant.NEW) }
        checked { io.syncDirectory() }
        checked { state.verify(slot, validatedBase) }
    }

    fun write(
        slot: AndroidCredentialSlot,
        bytes: ByteArray,
        previous: ByteArray?,
    ) = transaction.write(slot, bytes, previous)

    fun removeAll(slot: AndroidCredentialSlot) {
        // Never use AtomicFile.delete(): it ignores deletion results for all three paths.
        for (variant in REMOVAL_ORDER) {
            checked { io.remove(slot, variant) }
            checked { io.syncDirectory() }
            if (checked { io.exists(slot, variant) }) {
                androidCredentialPermanent(InstallationPermanentFailure.READ_BACK_MISMATCH)
            }
        }
        proveAbsent(slot)
    }

    private fun sameBase(
        slot: AndroidCredentialSlot,
        expected: ByteArray,
    ): Boolean = read(slot, AndroidCredentialVariant.BASE)?.contentEquals(expected) == true

    private fun <T> checked(operation: () -> T): T {
        checkActive()
        return operation().also { checkActive() }
    }

    companion object {
        private val REMOVAL_ORDER =
            listOf(AndroidCredentialVariant.NEW, AndroidCredentialVariant.BACKUP, AndroidCredentialVariant.BASE)
    }
}

/** Raw fixed-piece comparisons, shared by active reads and cancellation-independent rollback proof. */
private class AndroidCredentialFileState(
    private val io: AndroidCredentialFileIo,
) {
    fun verify(
        slot: AndroidCredentialSlot,
        expected: ByteArray?,
    ) {
        val pieces = pieces(slot)
        if (pieces.pending || pieces.backup || pieces.base != (expected != null)) {
            androidCredentialPermanent(InstallationPermanentFailure.READ_BACK_MISMATCH)
        }
        val actual = read(slot, AndroidCredentialVariant.BASE)
        if (expected == null) {
            if (actual != null) androidCredentialPermanent(InstallationPermanentFailure.READ_BACK_MISMATCH)
        } else if (actual == null || !actual.contentEquals(expected)) {
            androidCredentialPermanent(InstallationPermanentFailure.READ_BACK_MISMATCH)
        }
    }

    fun pieces(slot: AndroidCredentialSlot): AndroidCredentialPieces =
        AndroidCredentialPieces(
            io.exists(slot, AndroidCredentialVariant.BASE),
            io.exists(slot, AndroidCredentialVariant.NEW),
            io.exists(slot, AndroidCredentialVariant.BACKUP),
        )

    fun read(
        slot: AndroidCredentialSlot,
        variant: AndroidCredentialVariant,
    ): ByteArray? {
        val bytes = io.readBounded(slot, variant, slot.byteLimit)
        if (bytes != null && bytes.size > slot.byteLimit) {
            androidCredentialPermanent(InstallationPermanentFailure.TOO_LARGE)
        }
        return bytes
    }
}

/** Owns one stream through finish/rollback, including cancellation at native-call boundaries. */
private class AndroidCredentialAtomicTransaction(
    private val io: AndroidCredentialFileIo,
    private val state: AndroidCredentialFileState,
    private val checkActive: () -> Unit,
    private val files: AndroidCredentialAtomicFile,
) {
    // Native failures must close the owned stream without replacing the original cancellation.
    @Suppress("TooGenericExceptionCaught")
    fun write(
        slot: AndroidCredentialSlot,
        bytes: ByteArray,
        previous: ByteArray?,
    ) {
        if (bytes.size > slot.byteLimit) androidCredentialPermanent(InstallationPermanentFailure.TOO_LARGE)
        checked { state.verify(slot, previous) }
        var transaction: AndroidCredentialWrite? = null
        var finishAttempted = false
        try {
            checkActive()
            // Assign before checking cancellation: the owned stream must remain reachable for cleanup.
            transaction = io.startWrite(slot)
            checkActive()
            transaction.write(bytes)
            checkActive()
            transaction.sync()
            checkActive()
            finishAttempted = true
            transaction.finish()
            checkActive()
            if (!transaction.isClosed()) androidCredentialPermanent(InstallationPermanentFailure.READ_BACK_MISMATCH)
            files.confirm(slot, bytes)
        } catch (cancelled: CancellationException) {
            try {
                cleanFailedWrite(slot, previous, transaction, finishAttempted)
            } catch (_: Exception) {
                // Preserve the original cancellation identity, including when cleanup also fails.
            }
            throw cancelled
        } catch (failure: Exception) {
            if (!cleanFailedWrite(slot, previous, transaction, finishAttempted)) {
                androidCredentialTemporary(InstallationTemporaryFailure.UNCERTAIN)
            }
            throw failure
        }
    }

    private fun cleanFailedWrite(
        slot: AndroidCredentialSlot,
        previous: ByteArray?,
        transaction: AndroidCredentialWrite?,
        finishAttempted: Boolean,
    ): Boolean =
        try {
            // No coroutine-active check during resource cleanup. An ambiguous commit is never rolled back.
            if (!finishAttempted) transaction?.rollback()
            if (transaction != null && !transaction.isClosed()) transaction.close()
            if (transaction != null && !transaction.isClosed()) {
                false
            } else {
                if (!finishAttempted) {
                    state.verify(slot, previous)
                    io.syncDirectory()
                    state.verify(slot, previous)
                }
                true
            }
        } catch (cancelled: CancellationException) {
            try {
                closeAfterFailure(transaction)
            } catch (_: Exception) {
                // A secondary resource failure must not replace this cancellation either.
            }
            throw cancelled
        } catch (_: Exception) {
            closeAfterFailure(transaction)
            false
        }

    private fun closeAfterFailure(transaction: AndroidCredentialWrite?) {
        try {
            if (transaction != null && !transaction.isClosed()) transaction.close()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // The caller reports UNCERTAIN and retains evidence; it cannot report Stored.
        }
    }

    private fun <T> checked(operation: () -> T): T {
        checkActive()
        return operation().also { checkActive() }
    }
}

internal class AndroidNativeCredentialFileIo(
    context: Context,
) : AndroidCredentialFileIo {
    private val paths = AndroidCredentialFilePaths(context)

    override fun prepareDirectory() = paths.prepare()

    override fun exists(
        slot: AndroidCredentialSlot,
        variant: AndroidCredentialVariant,
    ): Boolean {
        val found = paths.stat(paths.path(slot, variant)) ?: return false
        paths.requireFile(found)
        return true
    }

    override fun readBounded(
        slot: AndroidCredentialSlot,
        variant: AndroidCredentialVariant,
        limit: Int,
    ): ByteArray? {
        val file = paths.path(slot, variant)
        val before = paths.stat(file) ?: return null
        paths.requireFile(before)
        paths.requireBound(before, limit)
        return nativeDescriptor(file, OsConstants.O_RDONLY or OsConstants.O_NOFOLLOW or OsConstants.O_CLOEXEC) { fd ->
            val opened = Os.fstat(fd)
            paths.requireFile(opened)
            paths.requireSameFile(before, opened)
            paths.requireBound(opened, limit)
            val bytes = ByteArray(opened.st_size.toInt())
            var offset = 0
            while (offset < bytes.size) {
                val count = Os.read(fd, bytes, offset, bytes.size - offset)
                if (count <= 0) androidCredentialPermanent(InstallationPermanentFailure.CORRUPT)
                offset += count
            }
            if (Os.read(fd, ByteArray(1), 0, 1) != 0) {
                androidCredentialPermanent(InstallationPermanentFailure.STATE_CHANGED)
            }
            val after = Os.fstat(fd)
            paths.requireSameFile(opened, after)
            if (after.st_size != opened.st_size) androidCredentialPermanent(InstallationPermanentFailure.STATE_CHANGED)
            paths.requireSameFile(
                after,
                paths.stat(file) ?: androidCredentialTemporary(InstallationTemporaryFailure.UNCERTAIN),
            )
            bytes
        }
    }

    override fun syncFile(slot: AndroidCredentialSlot) {
        val file = paths.path(slot, AndroidCredentialVariant.BASE)
        val before = paths.stat(file) ?: androidCredentialTemporary(InstallationTemporaryFailure.UNCERTAIN)
        paths.requireFile(before)
        nativeDescriptor(file, OsConstants.O_RDONLY or OsConstants.O_NOFOLLOW or OsConstants.O_CLOEXEC) { fd ->
            paths.requireSameFile(before, Os.fstat(fd))
            Os.fsync(fd)
        }
    }

    override fun syncDirectory() = paths.syncDirectory(paths.checkedDirectory(), privatePermissions = true)

    // Keep the captured stream reachable on construction failure; the original cancellation wins.
    @Suppress("TooGenericExceptionCaught", "InstanceOfCheckForException")
    override fun startWrite(slot: AndroidCredentialSlot): AndroidCredentialWrite {
        val base = paths.path(slot, AndroidCredentialVariant.BASE)
        // .bak must never reach AndroidX startWrite's silent legacy promotion path.
        if (exists(slot, AndroidCredentialVariant.NEW) || exists(slot, AndroidCredentialVariant.BACKUP)) {
            androidCredentialPermanent(InstallationPermanentFailure.STATE_CHANGED)
        }
        val atomic = AtomicFile(base)
        val stream = atomic.startWrite()
        try {
            val fd = stream.fd
            val opened = Os.fstat(fd)
            paths.requireRegularOwnedFile(opened)
            Os.fchmod(fd, AndroidCredentialFilePaths.FILE_MODE)
            val pending =
                paths.stat(paths.path(slot, AndroidCredentialVariant.NEW))
                    ?: androidCredentialTemporary(InstallationTemporaryFailure.UNCERTAIN)
            paths.requireFile(pending)
            paths.requireSameFile(opened, pending)
            return AndroidNativeCredentialWrite(atomic, stream, fd)
        } catch (failure: Exception) {
            try {
                stream.close()
            } catch (cancelled: CancellationException) {
                if (failure !is CancellationException) throw cancelled
            } catch (_: Exception) {
                // No success is possible. Keep the orphan .new for checked recovery.
            }
            throw failure
        }
    }

    override fun remove(
        slot: AndroidCredentialSlot,
        variant: AndroidCredentialVariant,
    ) {
        val file = paths.path(slot, variant)
        val before = paths.stat(file) ?: return
        paths.requireFile(before)
        try {
            Os.remove(file.absolutePath)
        } catch (failure: ErrnoException) {
            if (failure.errno != OsConstants.ENOENT) throw failure
        }
    }
}

/** Context-derived fixed child paths and public Android metadata/durability operations only. */
private class AndroidCredentialFilePaths(
    context: Context,
) {
    private val deviceProtectedContext = context.isDeviceProtectedStorage
    private val context = context.applicationContext
    private var directory: File? = null

    fun prepare() {
        if (deviceProtectedContext || context.isDeviceProtectedStorage) {
            androidCredentialPermanent(InstallationPermanentFailure.UNSUPPORTED)
        }
        val user = context.getSystemService(UserManager::class.java)
        if (user == null || !user.isUserUnlocked) androidCredentialTemporary(InstallationTemporaryFailure.LOCKED)
        val root = context.noBackupFilesDir
        val parent = root.parentFile ?: androidCredentialPermanent(InstallationPermanentFailure.UNSUPPORTED)
        requireDirectory(root, privatePermissions = false)
        val child = File(root, DIRECTORY)
        if (stat(child) == null) {
            try {
                Os.mkdir(child.absolutePath, DIRECTORY_MODE)
            } catch (failure: ErrnoException) {
                if (failure.errno != OsConstants.EEXIST) throw failure
            }
        }
        requireDirectory(child, privatePermissions = true)
        // Context may have just created noBackupFilesDir itself. Sync both levels and its parent.
        syncDirectory(child, privatePermissions = true)
        syncDirectory(root, privatePermissions = false)
        syncDirectory(parent, privatePermissions = false)
        directory = child
    }

    fun path(
        slot: AndroidCredentialSlot,
        variant: AndroidCredentialVariant,
    ): File = File(checkedDirectory(), slot.fileName + variant.suffix)

    fun checkedDirectory(): File {
        val child = directory ?: androidCredentialPermanent(InstallationPermanentFailure.UNSUPPORTED)
        requireDirectory(child, privatePermissions = true)
        return child
    }

    fun syncDirectory(
        file: File,
        privatePermissions: Boolean,
    ) {
        val before = requireDirectory(file, privatePermissions)
        nativeDescriptor(file, DIRECTORY_READ_FLAGS) { fd ->
            val opened = Os.fstat(fd)
            if (!OsConstants.S_ISDIR(opened.st_mode)) {
                androidCredentialPermanent(InstallationPermanentFailure.UNSUPPORTED)
            }
            requireSameFile(before, opened)
            Os.fsync(fd)
        }
    }

    fun stat(file: File): StructStat? =
        try {
            Os.lstat(file.absolutePath)
        } catch (failure: ErrnoException) {
            if (failure.errno != OsConstants.ENOENT) throw failure
            null
        }

    fun requireDirectory(
        file: File,
        privatePermissions: Boolean,
    ): StructStat {
        val value = stat(file) ?: androidCredentialTemporary(InstallationTemporaryFailure.IO_FAILURE)
        if (!OsConstants.S_ISDIR(value.st_mode) || value.st_uid != Process.myUid()) {
            androidCredentialPermanent(InstallationPermanentFailure.UNSUPPORTED)
        }
        if (privatePermissions &&
            value.st_mode and SHARED_PERMISSIONS != 0
        ) {
            androidCredentialPermanent(InstallationPermanentFailure.UNSUPPORTED)
        }
        return value
    }

    fun requireRegularOwnedFile(value: StructStat) {
        if (!OsConstants.S_ISREG(value.st_mode) || value.st_uid != Process.myUid() || value.st_nlink != 1L) {
            androidCredentialPermanent(InstallationPermanentFailure.UNSUPPORTED)
        }
    }

    fun requireFile(value: StructStat) {
        requireRegularOwnedFile(value)
        if (value.st_mode and SHARED_PERMISSIONS != 0) {
            androidCredentialPermanent(InstallationPermanentFailure.UNSUPPORTED)
        }
    }

    fun requireBound(
        value: StructStat,
        limit: Int,
    ) {
        if (value.st_size < 0) androidCredentialPermanent(InstallationPermanentFailure.CORRUPT)
        if (value.st_size > limit) androidCredentialPermanent(InstallationPermanentFailure.TOO_LARGE)
    }

    fun requireSameFile(
        first: StructStat,
        second: StructStat,
    ) {
        if (first.st_ino != second.st_ino || first.st_dev != second.st_dev) {
            androidCredentialPermanent(InstallationPermanentFailure.STATE_CHANGED)
        }
    }

    companion object {
        private val DIRECTORY_READ_FLAGS =
            OsConstants.O_RDONLY or OsConstants.O_NOFOLLOW or OsConstants.O_CLOEXEC
        private const val DIRECTORY = "complaint-installation-v1"
        private const val DIRECTORY_MODE = 448 // 0700
        const val FILE_MODE = 384 // 0600
        private const val SHARED_PERMISSIONS = 63 // 0077
    }
}

// Close the exact opened FD. Report close exceptions after success, retaining primary cancellation identity.
@Suppress("TooGenericExceptionCaught", "InstanceOfCheckForException", "ThrowingExceptionFromFinally")
private fun <T> nativeDescriptor(
    file: File,
    flags: Int,
    operation: (FileDescriptor) -> T,
): T {
    val fd = Os.open(file.absolutePath, flags, 0)
    var primaryFailure: Throwable? = null
    try {
        return operation(fd)
    } catch (failure: Throwable) {
        primaryFailure = failure
        throw failure
    } finally {
        try {
            Os.close(fd)
        } catch (failure: Exception) {
            if (primaryFailure == null ||
                (failure is CancellationException && primaryFailure !is CancellationException)
            ) {
                throw failure
            }
        }
    }
}

private class AndroidNativeCredentialWrite(
    private val atomic: AtomicFile,
    private val stream: FileOutputStream,
    private val descriptor: FileDescriptor,
) : AndroidCredentialWrite {
    override fun write(bytes: ByteArray) = stream.write(bytes)

    override fun sync() = descriptor.sync()

    override fun finish() = atomic.finishWrite(stream)

    override fun rollback() = atomic.failWrite(stream)

    override fun isClosed(): Boolean = !descriptor.valid()

    override fun close() = stream.close()
}
