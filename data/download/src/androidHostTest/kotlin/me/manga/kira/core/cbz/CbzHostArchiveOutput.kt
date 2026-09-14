package me.manga.kira.core.cbz

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING

/**
 * Real host-file promotion of the manager's owned sibling paths; the real ZIP sink is inherited.
 * This does not qualify Android Os.rename, native region decoding, or native AVIF pixels.
 * Atomic-move failures propagate without a copy/delete fallback or any post-commit callback.
 */
internal open class CbzHostArchiveOutput : CbzArchiveOutput() {
    override fun publish(
        temporary: File,
        destination: File,
    ) {
        Files.move(temporary.toPath(), destination.toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
    }
}
