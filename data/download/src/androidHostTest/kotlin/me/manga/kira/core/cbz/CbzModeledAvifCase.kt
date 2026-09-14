package me.manga.kira.core.cbz

import android.graphics.Bitmap
import android.graphics.Rect
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Full AVIF decode is modeled; real crop/compress checks ownership, not native AVIF pixels. */
internal class CbzModeledAvifCase(
    private val fixture: CbzHostFixture,
    private val height: Int,
    private val chapter: Long,
) {
    private val source =
        File(fixture.directory(chapter), "modeled.avif").apply {
            writeBytes(byteArrayOf(0, 0, 0, 12) + "ftypavif".toByteArray(Charsets.US_ASCII))
        }
    private val parent = AtomicReference<Bitmap>()
    private val crops = CopyOnWriteArrayList<Bitmap>()
    private val decodes = AtomicInteger()
    private val decoder =
        object : CbzImageDecoder() {
            override suspend fun decodeAvif(file: File): Bitmap {
                assertEquals(source, file)
                decodes.incrementAndGet()
                return Bitmap.createBitmap(3, height, Bitmap.Config.ARGB_8888).also(parent::set)
            }

            override fun crop(
                parent: Bitmap,
                region: Rect,
            ): Bitmap {
                assertFalse(parent.isRecycled)
                crops.lastOrNull()?.let { assertTrue(it.isRecycled) }
                return super.crop(parent, region).also { crops += it }
            }
        }
    private val encodes = AtomicInteger()

    suspend fun verify(scope: CoroutineScope) {
        CbzEncodeGate().use { gate ->
            val manager = createManager(gate)
            val conversion =
                scope.async { manager.createCbzParallel(listOf(source.absolutePath), fixture.mangaId, chapter) }
            try {
                gate.awaitEntry()
                assertEquals(1, decodes.get())
                assertEquals(if (height > 6000) 1 else 0, crops.size)
            } finally {
                gate.close()
            }
            conversion.await()
        }
        assertCompleted()
    }

    private fun createManager(gate: CbzEncodeGate): OptimizedCbzManager =
        OptimizedCbzManager(fixture.context, cbzTier(), decoder) { bitmap, format, quality, stream ->
            assertFalse(assertNotNull(parent.get()).isRecycled)
            if (height <= 6000) assertSame(parent.get(), bitmap)
            if (encodes.incrementAndGet() == 1) gate.hold()
            bitmap.compress(format, quality, stream)
        }

    private fun assertCompleted() {
        assertTrue(assertNotNull(parent.get()).isRecycled)
        assertTrue(crops.all(Bitmap::isRecycled))
        fixture.assertArchive(if (height > 6000) listOf(3 to 6000, 3 to 5) else listOf(3 to height), chapter)
        fixture.assertNoTemporary(chapter)
    }
}
