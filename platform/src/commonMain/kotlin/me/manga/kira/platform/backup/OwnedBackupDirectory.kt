package me.manga.kira.platform.backup

import okio.Closeable
import okio.FileSystem
import okio.IOException
import okio.Path
import kotlin.random.Random

/**
 * Cleanup capability for one exclusively created directory. Names within it are application-owned,
 * never archive paths. Closing cannot remove a colliding/pre-existing directory or another import.
 */
class OwnedBackupDirectory private constructor(
    private val system: FileSystem,
    val path: Path,
) : Closeable {
    private var closed = false

    override fun close() {
        if (closed) return
        system.deleteRecursively(path, mustExist = false)
        closed = true
    }

    companion object {
        /** Creates the parent if needed, but acquires ownership only after exclusive child creation. */
        fun create(
            system: FileSystem,
            parent: Path,
        ): OwnedBackupDirectory {
            system.createDirectories(parent)
            val child = parent / "import-${Random.nextLong().toULong()}-${Random.nextLong().toULong()}"
            try {
                system.createDirectory(child, mustCreate = true)
            } catch (failure: IOException) {
                // No cleanup: exclusive creation did not establish that this path belongs to us.
                throw failure
            }
            return OwnedBackupDirectory(system, child)
        }
    }
}
