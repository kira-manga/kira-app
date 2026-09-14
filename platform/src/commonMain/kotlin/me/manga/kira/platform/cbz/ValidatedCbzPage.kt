package me.manga.kira.platform.cbz

import me.manga.kira.platform.media.PageImageMetadata

/**
 * The retained encoded snapshot and the inspector's metadata for those exact bytes travel together.
 * This view neither copies nor validates: the writer must inspect first and keep [bytes] immutable
 * until encoding and all sink callbacks finish. Admission is not a substitute for that validation.
 */
internal class ValidatedCbzPage(
    val bytes: ByteArray,
    val metadata: PageImageMetadata,
)
