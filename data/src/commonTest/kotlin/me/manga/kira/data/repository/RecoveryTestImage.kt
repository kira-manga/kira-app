package me.manga.kira.data.repository

import me.manga.kira.platform.media.PageImageFormat
import me.manga.kira.platform.media.PageImageMetadata
import me.manga.kira.platform.media.PageInspection
import me.manga.kira.platform.media.PageInvalidReason
import me.manga.kira.platform.media.PageMediaInspector
import me.manga.kira.platform.media.readPageSnapshot
import okio.ByteString.Companion.decodeBase64
import okio.FileSystem
import okio.Path

/** Fixed 1x1 PNG; shipping/native adapters, not this protocol fake, establish decoder support. */
internal fun recoveryTestPng(): ByteArray = requireNotNull(
    "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=".decodeBase64(),
).toByteArray()

/** Exact-fixture fake for common routing tests ONLY. It cannot qualify native media validation. */
internal class RecoveryFixtureInspector(private val system: FileSystem) : PageMediaInspector {
    override fun inspect(encoded: ByteArray): PageInspection =
        if (encoded.contentEquals(recoveryTestPng())) {
            PageInspection.Valid(PageImageMetadata(PageImageFormat.PNG, 1, 1))
        } else {
            PageInspection.Invalid(PageInvalidReason.INCOMPLETE_OR_CORRUPT)
        }

    override fun inspect(path: Path): PageInspection = inspect(readPageSnapshot(system, path))
}
