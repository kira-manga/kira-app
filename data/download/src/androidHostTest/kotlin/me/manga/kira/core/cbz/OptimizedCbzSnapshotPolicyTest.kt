package me.manga.kira.core.cbz

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import me.manga.kira.platform.media.PageByteLimitExceeded
import me.manga.kira.platform.media.PageBytePolicy
import me.manga.kira.platform.media.PageInspection
import me.manga.kira.platform.media.PageMediaException
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import java.io.File
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Real host PNG inspection/codec/ZIP; not native AVIF, Android atomic rename, or measured peak-memory proof. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@LooperMode(LooperMode.Mode.PAUSED)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class OptimizedCbzSnapshotPolicyTest {
    @Test
    fun validOverBudgetPagePreservesExactBytesAndDetectedExtensionWithoutTranscode() =
        cbzHostTest { fixture ->
            val source = File(fixture.pages(count = 1).single())
            val mislabeled = File(source.parentFile, "actually-png.jpg")
            check(source.renameTo(mislabeled))
            val case = CbzPolicyCase(fixture, listOf(mislabeled.absolutePath))
            val inspector = CbzSnapshotObserver()

            val archive = case.convert(CbzPagePolicy(inspector = inspector, maxMemoryBytes = PRESERVE_ONLY_BUDGET))

            assertEquals(fixture.destination().absolutePath, archive)
            assertContentEquals(case.originals.single(), case.assertCommitted(listOf("page_0000.png")).single())
            case.assertNoTranscode()
            assertNotEquals(mislabeled, inspector.inspected.single())
            assertFalse(inspector.inspected.single().exists())
        }

    @Test
    fun mixedArchiveKeepsPreservedAndTranscodedPagesInInputOrder() =
        cbzHostTest { fixture ->
            val paths =
                fixture.pages(count = 1, width = LARGE_PAGE_SIDE, height = LARGE_PAGE_SIDE) +
                    fixture.pages(count = 1, chapter = 2L)
            val decoder = CbzObservedDecoder(listOf(paths.last()))
            val case = CbzPolicyCase(fixture, paths, decoder)

            case.convert(CbzPagePolicy(maxMemoryBytes = MIXED_PAGE_BUDGET))

            val entries = case.assertCommitted(listOf("page_0000.png", "page_0001.webp"))
            assertContentEquals(case.originals.first(), entries.first())
            assertWebpDimensions(entries.last(), CBZ_SMALL_PAGE_WIDTH, CBZ_SMALL_PAGE_HEIGHT)
            assertEquals(1, case.encodes.get())
            assertEquals(1, decoder.requested.size)
            assertTrue(decoder.bitmaps.all(Bitmap::isRecycled))
            fixture.assertNoTemporary(2L)
        }

    @Test
    fun laterInvalidPageRollsBackAnAlreadyPreservedEntryWithoutReplacingOldZip() =
        cbzHostTest { fixture ->
            val paths = fixture.pages()
            File(paths.last()).writeText("not an image")
            val case = CbzPolicyCase(fixture, paths)

            val failure =
                assertFailsWith<PageMediaException> {
                    case.convert(CbzPagePolicy(maxMemoryBytes = PRESERVE_ONLY_BUDGET))
                }

            assertIs<PageInspection.Invalid>(failure.inspection)
            assertEquals(listOf(1), case.progress, "The first entry must have been staged before the later rejection")
            case.assertNoTranscode()
            case.assertRolledBack()
        }

    @Test
    fun oversizedSourceIsRejectedBeforeSnapshotInspectionOrTranscode() =
        cbzHostTest { fixture ->
            val paths = fixture.pages(count = 1)
            val case = CbzPolicyCase(fixture, paths)
            val limit = case.originals.single().size.toLong() - 1
            val inspector = CbzSnapshotObserver()
            val policy = CbzPagePolicy(inspector = inspector, bytePolicy = PageBytePolicy(limit))

            val failure = assertFailsWith<PageByteLimitExceeded> { case.convert(policy) }

            assertEquals(limit, failure.limit)
            assertEquals(limit + 1, failure.observedBytes)
            assertTrue(inspector.inspected.isEmpty())
            assertTrue(case.progress.isEmpty())
            case.assertNoTranscode()
            case.assertRolledBack()
        }

    @Test
    fun originalMutationDuringInspectionCannotChangeTheValidatedPreservedSnapshot() =
        cbzHostTest { fixture ->
            val source = File(fixture.pages(count = 1).single())
            val case = CbzPolicyCase(fixture, listOf(source.absolutePath))
            val original = case.originals.single()
            val inspector =
                CbzSnapshotObserver { snapshot ->
                    assertNotEquals(source, snapshot)
                    assertContentEquals(original, snapshot.readBytes())
                    source.writeText("mutated after snapshot creation")
                    assertContentEquals(original, snapshot.readBytes())
                }

            case.convert(CbzPagePolicy(inspector = inspector, maxMemoryBytes = PRESERVE_ONLY_BUDGET))

            assertContentEquals(original, case.assertCommitted(listOf("page_0000.png")).single())
            assertFalse(inspector.inspected.single().exists())
            case.assertNoTranscode()
        }
}

private fun assertWebpDimensions(
    bytes: ByteArray,
    width: Int,
    height: Int,
) {
    val bitmap = assertNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size))
    try {
        assertEquals(width to height, bitmap.width to bitmap.height)
    } finally {
        bitmap.recycle()
    }
}

private const val PRESERVE_ONLY_BUDGET = 1L
private const val MIXED_PAGE_BUDGET = 5L * 1024 * 1024
private const val LARGE_PAGE_SIDE = 1024
