package me.manga.kira.core.cbz

import me.manga.kira.platform.media.PageImageFormat
import me.manga.kira.platform.media.PageImageMetadata
import me.manga.kira.platform.media.PageInspection
import me.manga.kira.platform.media.PageMediaInspector
import okio.Path
import java.io.File
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull

/** Only the 12-byte AVIF model is accepted here; neither this metadata nor its pixels are native proof. */
internal class CbzModeledAvifInspector(
    private val source: File,
    private val height: Int,
) : PageMediaInspector {
    private val expected = byteArrayOf(0, 0, 0, CBZ_AVIF_HEADER_LENGTH) + "ftypavif".toByteArray(Charsets.US_ASCII)
    private val inspected = AtomicReference<File>()

    override fun inspect(encoded: ByteArray): PageInspection {
        assertContentEquals(expected, encoded)
        return PageInspection.Valid(PageImageMetadata(PageImageFormat.AVIF, CBZ_AVIF_PAGE_WIDTH, height))
    }

    override fun inspect(path: Path): PageInspection {
        val snapshot = path.toFile()
        assertNotEquals(source, snapshot)
        assertEquals(source.parentFile, snapshot.parentFile)
        inspected.set(snapshot)
        return inspect(snapshot.readBytes())
    }

    fun assertDecoderSnapshot(snapshot: File) {
        assertEquals(assertNotNull(inspected.get()), snapshot, "Decoder must receive the inspected owned file")
        assertContentEquals(expected, snapshot.readBytes())
    }

    fun assertReleased() {
        assertFalse(assertNotNull(inspected.get()).exists())
    }
}
