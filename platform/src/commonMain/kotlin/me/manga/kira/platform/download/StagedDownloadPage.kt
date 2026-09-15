package me.manga.kira.platform.download

import me.manga.kira.platform.media.PageImageMetadata
import me.manga.kira.platform.media.publishPageSnapshot
import okio.FileSystem
import okio.Path

/**
 * A validated, privately retained OS snapshot, not a live chapter file. The data-layer listener
 * owns disposal after handoff and may publish only while holding its original attempt's file gate.
 */
class StagedDownloadPage(
    private val system: FileSystem,
    val path: Path,
    val metadata: PageImageMetadata,
) {
    private var owned = true

    fun publish(directory: Path, pageIndex: Int): Path {
        check(owned)
        return publishPageSnapshot(system, path, pageIndex, metadata, directory).also { owned = false }
    }

    fun discard() {
        if (owned) {
            system.delete(path, mustExist = false)
            owned = false
        }
    }
}
