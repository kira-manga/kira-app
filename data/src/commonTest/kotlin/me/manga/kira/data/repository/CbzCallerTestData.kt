package me.manga.kira.data.repository

import kotlinx.coroutines.Dispatchers
import me.manga.kira.core.dispatchers.DispatcherProvider
import me.manga.kira.platform.backup.BackupZipWriter
import me.manga.kira.platform.media.PageImageFormat
import me.manga.kira.platform.media.PageImageMetadata
import me.manga.kira.platform.media.PageInspection
import me.manga.kira.platform.media.PageMediaInspector
import okio.Buffer
import okio.ByteString.Companion.decodeBase64
import okio.Path

/** Existing caller fixture PNG/ZIP bytes shared with progress-only common tests, not a codec fake. */
internal val CBZ_CALLER_PNG: ByteArray = checkNotNull(
    "iVBORw0KGgoAAAANSUhEUgAAAAgAAAAIAQAAAADsdIMmAAAAC0lEQVR42mNgQAUAABAAAaoZ+IIAAAAASUVORK5CYII=".decodeBase64(),
).toByteArray()

internal fun cbzCallerArchiveBytes(pages: List<ByteArray> = listOf(CBZ_CALLER_PNG), sizeBytes: Int? = null): ByteArray {
    fun archive(padding: Int?): ByteArray {
        val buffer = Buffer()
        BackupZipWriter(buffer).apply {
            pages.forEachIndexed { index, bytes -> writeEntryBytes("page_${index.toString().padStart(4, '0')}.png", bytes) }
            if (padding != null) writeEntryBytes("padding.bin", ByteArray(padding))
            finish()
        }
        return buffer.readByteArray()
    }
    if (sizeBytes == null) return archive(null)
    return archive(sizeBytes - archive(0).size).also { check(it.size == sizeBytes) }
}

/** Only unit-progress fakes use this known-byte inspector; Room/native proofs inject the real one. */
internal object CbzCallerKnownPngInspector : PageMediaInspector {
    override fun inspect(encoded: ByteArray): PageInspection {
        check(encoded.contentEquals(CBZ_CALLER_PNG))
        return PageInspection.Valid(PageImageMetadata(PageImageFormat.PNG, 8, 8))
    }
    override fun inspect(path: Path): PageInspection = error("Progress fixture inspects archive entry bytes only")
}

internal object CbzCallerDispatchers : DispatcherProvider {
    override val main get() = Dispatchers.Unconfined
    override val mainImmediate get() = Dispatchers.Unconfined
    override val default get() = Dispatchers.Unconfined
    override val io get() = Dispatchers.Unconfined
    override val unconfined get() = Dispatchers.Unconfined
}
