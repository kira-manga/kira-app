@file:Suppress("MagicNumber") // Fixed modest host fixtures, not production image sizing policy.

package me.manga.kira.core.cbz

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import me.manga.kira.core.util.heap.DeviceTier
import me.manga.kira.platform.device.DeviceTierProbe
import org.robolectric.RuntimeEnvironment
import java.io.File
import java.util.Random
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

internal fun cbzHostTest(block: suspend CoroutineScope.(CbzHostFixture) -> Unit) =
    runBlocking {
        CbzHostFixture().use { fixture ->
            withTimeout(CBZ_CASE_TIMEOUT_MILLIS) { block(fixture) }
        }
    }

/** No SQLite/framework substitution; uniquely owned real Android chapter directories only. */
internal class CbzHostFixture : AutoCloseable {
    val context: Context = RuntimeEnvironment.getApplication()
    val mangaId: Long = maxOf(1L, UUID.randomUUID().mostSignificantBits ushr 1)
    private val root = File(context.filesDir, "manga/$mangaId")

    init {
        check(!root.exists() && root.mkdirs())
    }

    fun directory(chapter: Long = 1L): File = File(root, "chapter_$chapter").apply { check(isDirectory || mkdir()) }

    fun destination(chapter: Long = 1L): File = File(directory(chapter), "chapter_$chapter.cbz")

    fun pages(
        count: Int = 2,
        width: Int = 32,
        height: Int = 33,
        chapter: Long = 1L,
        noisy: Boolean = false,
    ): List<String> =
        List(count) { index ->
            val file = File(directory(chapter), "input_$index.png")
            val bitmap = Bitmap.createBitmap(width + index, height, Bitmap.Config.ARGB_8888)
            try {
                if (noisy) {
                    fillCbzNoisyPage(bitmap, index)
                } else {
                    bitmap.eraseColor(0xff305070.toInt() + index)
                }
                file.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            } finally {
                bitmap.recycle()
            }
            file.absolutePath
        }

    fun priorArchive(chapter: Long = 1L): ByteArray {
        val bitmap = Bitmap.createBitmap(2, 3, Bitmap.Config.ARGB_8888)
        try {
            ZipOutputStream(destination(chapter).outputStream()).use { zip ->
                zip.putNextEntry(ZipEntry("page_0000.webp"))
                assertTrue(bitmap.compress(Bitmap.CompressFormat.WEBP_LOSSY, 75, zip))
                zip.closeEntry()
            }
        } finally {
            bitmap.recycle()
        }
        return destination(chapter).readBytes() // Small fixed sentinel, not production archive buffering.
    }

    fun assertArchive(
        dimensions: List<Pair<Int, Int>>,
        chapter: Long = 1L,
        verifyContent: (Int, Bitmap) -> Unit = { _, _ -> },
    ) {
        ZipFile(destination(chapter)).use { zip ->
            assertEquals(dimensions.size, zip.size())
            zip.entries().asSequence().forEachIndexed { index, entry ->
                assertEquals("page_${index.toString().padStart(4, '0')}.webp", entry.name)
                assertTrue(entry.size > 0)
                val bitmap = zip.getInputStream(entry).use { assertNotNull(BitmapFactory.decodeStream(it)) }
                try {
                    assertEquals(dimensions[index], bitmap.width to bitmap.height)
                    verifyContent(index, bitmap)
                } finally {
                    bitmap.recycle()
                }
            }
        }
    }

    fun assertNoTemporary(chapter: Long = 1L) {
        assertFalse(directory(chapter).listFiles().orEmpty().any { it.name.endsWith(".cbz.tmp") })
    }

    override fun close() {
        check(root.deleteRecursively())
    }
}

/** Eight 32px patches leave >=96.8% seeded noise in each 512px ordinary streaming fixture. */
private fun fillCbzNoisyPage(
    bitmap: Bitmap,
    page: Int,
) {
    val random = Random(page.toLong())
    val pixels = IntArray(bitmap.width * bitmap.height) { random.nextInt() or -0x1000000 }
    bitmap.setPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
    ordinaryPagePatches(bitmap, page).forEach { patch ->
        bitmap.setPixels(
            IntArray(CBZ_PIXEL_PATCH_SIZE * CBZ_PIXEL_PATCH_SIZE) { patch.color },
            0,
            CBZ_PIXEL_PATCH_SIZE,
            patch.x - CBZ_PIXEL_PATCH_SIZE / 2,
            patch.y - CBZ_PIXEL_PATCH_SIZE / 2,
            CBZ_PIXEL_PATCH_SIZE,
            CBZ_PIXEL_PATCH_SIZE,
        )
    }
}

/**
 * Real decoded ordinary WebP only: four distinct corners detect orientation; four gray bits
 * identify all twelve source pages independently of dimensions or ZIP names. Samples are >=12px
 * inside each flat patch, away from noisy edges/chroma bleed. A 32/255 per-channel tolerance allows
 * quality-70 YUV/quantization loss, but cannot confuse the marker levels separated by 192/255.
 */
internal fun assertCbzOrdinaryPageContent(
    page: Int,
    bitmap: Bitmap,
) {
    ordinaryPagePatches(bitmap, page).forEach { patch ->
        CBZ_PIXEL_SAMPLE_OFFSETS.forEach { dx ->
            CBZ_PIXEL_SAMPLE_OFFSETS.forEach { dy ->
                val actual = bitmap.getPixel(patch.x + dx, patch.y + dy)
                assertCbzPixel(patch.color, actual, "page $page patch (${patch.x}, ${patch.y}) offset ($dx, $dy)")
            }
        }
    }
}

private fun ordinaryPagePatches(
    bitmap: Bitmap,
    page: Int,
): List<CbzPixelPatch> =
    listOf(
        CbzPixelPatch(bitmap.width / 4, bitmap.height / 4, Color.rgb(224, 32, 32)),
        CbzPixelPatch(3 * bitmap.width / 4, bitmap.height / 4, Color.rgb(32, 224, 32)),
        CbzPixelPatch(bitmap.width / 4, 3 * bitmap.height / 4, Color.rgb(32, 32, 224)),
        CbzPixelPatch(3 * bitmap.width / 4, 3 * bitmap.height / 4, Color.rgb(224, 224, 32)),
    ) +
        List(4) { bit ->
            val gray = if ((page and (1 shl bit)) == 0) 32 else 224
            CbzPixelPatch(bitmap.width * (bit + 1) / 5, bitmap.height / 2, Color.rgb(gray, gray, gray))
        }

private fun assertCbzPixel(
    expected: Int,
    actual: Int,
    location: String,
) {
    assertEquals(255, Color.alpha(actual), location)
    assertCbzChannel(Color.red(expected), Color.red(actual), "$location red")
    assertCbzChannel(Color.green(expected), Color.green(actual), "$location green")
    assertCbzChannel(Color.blue(expected), Color.blue(actual), "$location blue")
}

private fun assertCbzChannel(
    expected: Int,
    actual: Int,
    location: String,
) {
    assertTrue(abs(expected - actual) <= CBZ_WEBP_CHANNEL_TOLERANCE, "$location expected ~$expected, got $actual")
}

private data class CbzPixelPatch(val x: Int, val y: Int, val color: Int)

private const val CBZ_PIXEL_PATCH_SIZE = 32
private const val CBZ_WEBP_CHANNEL_TOLERANCE = 32
private val CBZ_PIXEL_SAMPLE_OFFSETS = listOf(-4, 0, 4)

internal fun cbzTier(tier: DeviceTier = DeviceTier.LOW): DeviceTierProbe =
    object : DeviceTierProbe {
        override fun detect(): DeviceTier = tier
    }

internal class CbzEncodeGate : AutoCloseable {
    val entered = CompletableDeferred<Unit>()
    private val released = CountDownLatch(1)

    fun hold() {
        entered.complete(Unit)
        check(released.await(CBZ_GATE_TIMEOUT_SECONDS, TimeUnit.SECONDS)) { "CBZ encoder gate timed out" }
    }

    suspend fun awaitEntry() {
        withTimeout(CBZ_GATE_TIMEOUT_SECONDS * 1000) { entered.await() }
    }

    override fun close() = released.countDown()
}

internal const val CBZ_GATE_TIMEOUT_SECONDS = 15L
internal const val CBZ_CASE_TIMEOUT_MILLIS = 120_000L
