package me.manga.kira.platform.image

import android.app.Application
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.content.FileProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import me.manga.kira.core.platform.encodeImageBitmapToPng
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.random.Random

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AndroidScreenshotProviderTest {
    private val dispatcher = StandardTestDispatcher()
    private val application: Application get() = RuntimeEnvironment.getApplication()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        // The authority-only Robolectric overload fabricates ProviderInfo without URI grants.
        // Attach the actual merged manifest entry, including its production FileProvider paths.
        val info = checkNotNull(
            application.packageManager.resolveContentProvider(
                "${application.packageName}.fileprovider",
                PackageManager.GET_META_DATA,
            ),
        )
        assertTrue(info.grantUriPermissions)
        assertFalse(info.exported)
        Robolectric.buildContentProvider(FileProvider::class.java).create(info)
    }

    @After
    fun tearDown() {
        dispatcher.scheduler.runCurrent()
        Dispatchers.resetMain()
        // Only this Robolectric application's temporary cache, never application production data.
        File(application.cacheDir, "images").deleteRecursively()
    }

    @Test
    fun sameTitlePublishesClosedDistinctPngsAndKeepsEarlierBytes() = runTest(dispatcher) {
        val closed = AtomicBoolean(false)
        val ownerThread = Thread.currentThread()
        val context = RecordingShareContext(application) {
            assertTrue(closed.get())
            assertSame(ownerThread, Thread.currentThread())
        }
        val provider = AndroidScreenshotProvider(context) { closeObservedStream(it) { closed.set(true) } }
        val first = viewportPng()
        provider.shareBitmapBytes(first, SHARE_TITLE)
        val firstUri = sharedUri(context.choosers.single())
        closed.set(false)
        val second = smallPng()
        provider.shareBitmapBytes(second, SHARE_TITLE)
        val secondUri = sharedUri(context.choosers.last())
        val firstRead = readShared(firstUri)
        val secondRead = readShared(secondUri)
        assertNotEquals(firstUri, secondUri)
        assertArrayEquals(first, firstRead)
        assertArrayEquals(second, secondRead)
        assertPngSize(firstRead, VIEWPORT_WIDTH, VIEWPORT_HEIGHT)
        assertPngSize(secondRead, SMALL_SIDE, SMALL_SIDE)
    }

    @Test
    fun partialWriteFailureRemovesOnlyTheUnhandedFile() = runTest(dispatcher) {
        val context = RecordingShareContext(application)
        val original = smallPng()
        AndroidScreenshotProvider(context).shareBitmapBytes(original, SHARE_TITLE)
        val issued = sharedUri(context.choosers.single())
        var partial: File? = null
        val failing = AndroidScreenshotProvider(context) { file ->
            partial = file
            partialFailureStream(file)
        }
        failing.shareBitmapBytes(original, SHARE_TITLE)
        assertFalse(checkNotNull(partial).exists())
        assertEquals(1, context.choosers.size)
        assertArrayEquals(original, readShared(issued))
    }

    @Test
    fun cancellationAfterWritePropagatesAndCannotDispatchLate() = runTest(dispatcher) {
        val context = RecordingShareContext(application)
        var created: File? = null
        lateinit var worker: Job
        val cancelled = AtomicBoolean(false)
        val provider = AndroidScreenshotProvider(context) { file ->
            created = file
            closeObservedStream(file) { worker.cancel() }
        }
        worker = launch(start = CoroutineStart.LAZY) {
            try {
                provider.shareBitmapBytes(smallPng(), SHARE_TITLE)
            } catch (failure: CancellationException) {
                cancelled.set(true)
                throw failure
            }
        }
        worker.start()
        worker.join()
        assertTrue(worker.isCancelled)
        assertTrue(cancelled.get())
        assertTrue(context.choosers.isEmpty())
        assertFalse(checkNotNull(created).exists())
    }

    @Test
    fun failedChooserDeletesItsUnhandedFile() = runTest(dispatcher) {
        val context = RecordingShareContext(application) { throw ActivityNotFoundException("No receiver") }
        var created: File? = null
        val provider = AndroidScreenshotProvider(context) { file ->
            created = file
            FileOutputStream(file)
        }
        provider.shareBitmapBytes(smallPng(), SHARE_TITLE)
        assertTrue(context.choosers.isEmpty())
        assertFalse(checkNotNull(created).exists())
    }

    @Test
    fun cancellationAfterSuccessfulDispatchRetainsReadableContent() = runTest(dispatcher) {
        lateinit var worker: Job
        val context = RecordingShareContext(application) { worker.cancel() }
        val provider = AndroidScreenshotProvider(context)
        val bytes = smallPng()
        worker = launch(start = CoroutineStart.LAZY) { provider.shareBitmapBytes(bytes, SHARE_TITLE) }
        worker.start()
        worker.join()
        assertTrue(worker.isCancelled)
        val uri = sharedUri(context.choosers.single())
        assertArrayEquals(bytes, readShared(uri))
    }

    private fun readShared(uri: Uri): ByteArray =
        checkNotNull(application.contentResolver.openInputStream(uri)).use { it.readBytes() }
}

private class RecordingShareContext(
    context: Context,
    private val beforeDispatch: () -> Unit = {},
) : ContextWrapper(context) {
    val choosers = mutableListOf<Intent>()

    override fun getApplicationContext(): Context = this

    override fun startActivity(intent: Intent) {
        beforeDispatch()
        choosers += intent
    }
}

private fun closeObservedStream(file: File, onClosed: () -> Unit): OutputStream =
    object : FileOutputStream(file) {
        override fun close() {
            super.close()
            onClosed()
        }
    }

private fun partialFailureStream(file: File): OutputStream =
    object : FileOutputStream(file) {
        override fun write(bytes: ByteArray) {
            super.write(bytes, 0, bytes.size / 2)
            assertTrue(file.length() > 0)
            throw IOException("Partial PNG write")
        }
    }

private fun sharedUri(chooser: Intent): Uri {
    assertEquals(Intent.ACTION_CHOOSER, chooser.action)
    assertEquals(SHARE_TITLE, chooser.getStringExtra(Intent.EXTRA_TITLE))
    assertTrue(chooser.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
    val send = checkNotNull(chooser.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java))
    assertEquals(Intent.ACTION_SEND, send.action)
    assertEquals("image/png", send.type)
    assertTrue(send.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
    return checkNotNull(send.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java))
}

private fun viewportPng(): ByteArray {
    val bitmap = Bitmap.createBitmap(VIEWPORT_WIDTH, VIEWPORT_HEIGHT, Bitmap.Config.ARGB_8888)
    try {
        val random = Random(NOISE_SEED)
        val row = IntArray(VIEWPORT_WIDTH)
        repeat(VIEWPORT_HEIGHT) { y ->
            row.indices.forEach { x ->
                row[x] = Color.rgb(
                    random.nextInt(CHANNEL_SIZE),
                    random.nextInt(CHANNEL_SIZE),
                    random.nextInt(CHANNEL_SIZE),
                )
            }
            bitmap.setPixels(row, 0, row.size, 0, y, row.size, 1)
        }
        assertEquals(VIEWPORT_WIDTH * VIEWPORT_HEIGHT * ARGB_BYTES, bitmap.allocationByteCount)
        return checkNotNull(encodeImageBitmapToPng(bitmap.asImageBitmap())).also {
            assertTrue(
                "Exercise a nontrivial PNG, not a solid-color compression shortcut",
                it.size > VIEWPORT_WIDTH * VIEWPORT_HEIGHT,
            )
        }
    } finally {
        bitmap.recycle()
    }
}

private fun smallPng(): ByteArray {
    val bitmap = Bitmap.createBitmap(SMALL_SIDE, SMALL_SIDE, Bitmap.Config.ARGB_8888)
    try {
        bitmap.eraseColor(Color.BLUE)
        return checkNotNull(encodeImageBitmapToPng(bitmap.asImageBitmap()))
    } finally {
        bitmap.recycle()
    }
}

private fun assertPngSize(bytes: ByteArray, width: Int, height: Int) {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    assertEquals("image/png", bounds.outMimeType)
    assertEquals(width, bounds.outWidth)
    assertEquals(height, bounds.outHeight)
}

private const val SHARE_TITLE = "Share screenshot"
private const val VIEWPORT_WIDTH = 1080
private const val VIEWPORT_HEIGHT = 2400
private const val ARGB_BYTES = 4
private const val SMALL_SIDE = 2
private const val CHANNEL_SIZE = 256
private const val NOISE_SEED = 12
