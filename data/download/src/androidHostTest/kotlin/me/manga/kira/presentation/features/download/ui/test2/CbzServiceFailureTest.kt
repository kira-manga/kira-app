package me.manga.kira.presentation.features.download.ui.test2

import android.app.Application
import android.graphics.Bitmap
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import me.manga.kira.core.cbz.CBZ_CASE_TIMEOUT_MILLIS
import me.manga.kira.core.cbz.CbzEncodeGate
import me.manga.kira.core.cbz.OptimizedCbzManager
import me.manga.kira.core.cbz.cbzTier
import me.manga.kira.presentation.features.download.data.DownloadState
import me.manga.kira.presentation.features.download.data.DownloadingState
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Real CBZ-enabled service + Room persistence. Worker terminal SUCCESS remains a separate owner. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@LooperMode(LooperMode.Mode.PAUSED)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CbzServiceFailureTest {
    @Test
    fun cbzEncoderOomFailsWithoutPublishingLooseSuccessOrDeletingInputs() =
        cbzServiceTest {
            val encoded = AtomicReference<Bitmap>()
            val oom = OutOfMemoryError("synthetic App64 encoder OOM")
            val manager =
                OptimizedCbzManager(
                    storage.context,
                    cbzTier(),
                    encode = { bitmap, _, _, _ ->
                        encoded.set(bitmap)
                        throw oom
                    },
                )
            val states = download(manager).toList()
            assertIs<DownloadState.Error>(states.last())
            assertTrue(states.none { it is DownloadState.Complete })
            assertTrue(assertNotNull(encoded.get()).isRecycled)
            storage.assertImages(paths)
            assertEquals(rows.original.saved, rows.saved())
            assertEquals(
                emptyList(),
                rows.db
                    .notificationDao()
                    .getNotificationByChapterId(rows.original.saved.id)
                    ?.localImagePaths
                    .orEmpty(),
            )
            assertFalse(rows.download().state == DownloadingState.SUCCESS)
            assertNoArchiveOrTemporary()
        }

    @Test
    fun anExceptionMentioningMemoryIsNotATypedBudgetPreservationResult() =
        cbzServiceTest {
            val manager =
                OptimizedCbzManager(storage.context, cbzTier(), encode = { _, _, _, _ ->
                    throw IllegalStateException("synthetic memory write failure")
                })
            val states = download(manager).toList()
            assertIs<DownloadState.Error>(states.last())
            assertTrue(states.none { it is DownloadState.Complete })
            assertEquals(rows.original.saved, rows.saved())
            storage.assertImages(paths)
            assertNoArchiveOrTemporary()
        }

    @Test
    fun cbzCancellationIsNotConvertedToLooseSuccessOrServiceDeletion() =
        cbzServiceTest {
            val states = mutableListOf<DownloadState>()
            val encodes = AtomicInteger()
            val encoded = AtomicReference<Bitmap>()
            CbzEncodeGate().use { gate ->
                val manager =
                    OptimizedCbzManager(
                        storage.context,
                        cbzTier(),
                        encode = { bitmap, format, quality, output ->
                            encoded.set(bitmap)
                            encodes.incrementAndGet()
                            gate.hold()
                            bitmap.compress(format, quality, output)
                        },
                    )
                cancelAtEncode(download(manager), gate, states)
            }
            assertEquals(1, encodes.get())
            assertTrue(assertNotNull(encoded.get()).isRecycled)
            assertTrue(states.none { it is DownloadState.Complete || it is DownloadState.Error })
            storage.assertImages(paths)
            assertEquals(rows.original.saved, rows.saved())
            assertEquals(rows.original.download, rows.download())
            assertNoArchiveOrTemporary()
        }
}

private suspend fun CbzServiceCase.cancelAtEncode(
    flow: Flow<DownloadState>,
    gate: CbzEncodeGate,
    states: MutableList<DownloadState>,
) {
    coroutineScope {
        val collection = async { flow.toList(states) }
        try {
            gate.awaitEntry()
            storage.assertImages(paths)
            collection.cancel()
        } finally {
            gate.close()
        }
        assertFailsWith<CancellationException> { collection.await() }
        collection.join() // Wait for producer/manager cleanup, not just cancellation delivery.
    }
}

private fun cbzServiceTest(block: suspend CbzServiceCase.() -> Unit) =
    runBlocking {
        CancellationFixtureStorage(RuntimeEnvironment.getApplication()).use { storage ->
            val commit = NativeCommitGate()
            DownloadWorkerCancellationRows(storage, commit).use { rows ->
                withTimeout(CBZ_CASE_TIMEOUT_MILLIS) {
                    rows.seed()
                    storage.settings.setUseCbzFormat(true)
                    withServiceTransport(storage, rows, commit, block)
                }
            }
        }
    }

private suspend fun withServiceTransport(
    storage: CancellationFixtureStorage,
    rows: DownloadWorkerCancellationRows,
    commit: NativeCommitGate,
    block: suspend CbzServiceCase.() -> Unit,
) {
    CompleteSendDispatcher(DownloadJobWitness("app64-service-producer")).use { sender ->
        val seam = CancellationSeam.COMMITTED_RETURN // No HTTP gate and no worker commit in these cases.
        val dao = DownloadWorkerCancellationDao(rows, seam, DownloadJobWitness("unused"), sender, commit)
        CancellationPageTransport(seam, rows).use { transport ->
            CbzServiceCase(storage, rows, dao, transport, sender).block()
        }
    }
}

/** Composition of the existing App75 fixture, without rebuilding its service or worker policies. */
private class CbzServiceCase(
    val storage: CancellationFixtureStorage,
    val rows: DownloadWorkerCancellationRows,
    private val dao: DownloadWorkerCancellationDao,
    private val transport: CancellationPageTransport,
    private val sender: CompleteSendDispatcher,
) {
    val paths: List<String> get() = storage.imagePaths(rows.original.saved.id, PARTIAL_PAGES)

    suspend fun download(manager: OptimizedCbzManager): Flow<DownloadState> {
        val pages =
            assertNotNull(
                transport.provider.pagesOrNull(
                    FIXTURE_API,
                    rows.manga.url,
                    rows.manga.language,
                    rows.original.saved.url,
                ),
            )
        return fixtureDownloadService(
            CancellationFixtureServiceInputs(storage, rows, dao, transport, sender),
            manager,
        ).downloadChapterC(rows.original.saved, pages)
    }

    fun assertNoArchiveOrTemporary() {
        val directory = File(storage.mangaDirectory, "chapter_${rows.original.saved.id}")
        assertFalse(File(directory, "chapter_${rows.original.saved.id}.cbz").exists())
        assertFalse(directory.listFiles().orEmpty().any { it.name.endsWith(".cbz.tmp") })
    }
}
