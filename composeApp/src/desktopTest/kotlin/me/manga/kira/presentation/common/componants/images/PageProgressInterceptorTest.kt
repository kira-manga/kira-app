package me.manga.kira.presentation.common.componants.images

import coil3.ImageLoader
import coil3.PlatformContext
import coil3.Uri
import coil3.asImage
import coil3.decode.DataSource
import coil3.fetch.Fetcher
import coil3.fetch.ImageFetchResult
import coil3.intercept.Interceptor
import coil3.memory.MemoryCache
import coil3.network.NetworkHeaders
import coil3.network.httpHeaders
import coil3.request.ErrorResult
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.size.Size
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import me.manga.kira.data.repository.PageProgressRepositoryImpl
import me.manga.kira.domain.model.reader.PageDownloadProgress
import me.manga.kira.domain.model.reader.PageProgressAttempt
import me.manga.kira.domain.model.reader.PageProgressHandle
import me.manga.kira.ui.reader.pageProgressHandle
import org.jetbrains.skia.Bitmap
import java.io.Closeable
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame

private const val PAGE_URL = "https://reader.test/page.png"

@OptIn(ExperimentalCoroutinesApi::class)
class PageProgressInterceptorTest {
    private val dispatcher = StandardTestDispatcher()

    @BeforeTest
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun eachExecutionIncludingRetryAndMemoryCacheHitGetsAFreshTokenWithoutChangingCacheIdentity() = runTest(dispatcher) {
        val repository = PageProgressRepositoryImpl()
        val owner = repository.observe(PAGE_URL)
        val executed = CopyOnWriteArrayList<ImageRequest>()
        ProgressImages(repository, Interceptor { chain ->
            executed += chain.request
            if (executed.size == 1) ErrorResult(null, chain.request, IllegalStateException("first attempt"))
            else chain.proceed()
        }).use { images ->
            val request = pageRequest(owner.handle)
            assertIs<ErrorResult>(images.loader.execute(request))
            assertEquals(PageDownloadProgress.Failed, owner.progress.first())
            val fetched = assertIs<SuccessResult>(images.loader.execute(request))
            val cached = assertIs<SuccessResult>(images.loader.execute(request))
            assertEquals(DataSource.NETWORK, fetched.dataSource)
            assertEquals(DataSource.MEMORY_CACHE, cached.dataSource)
            assertEquals(1, images.fetchCount)
            assertEquals(fetched.memoryCacheKey, cached.memoryCacheKey)
            assertEquals(PageDownloadProgress.Complete, owner.progress.first())
            assertExecutionIdentities(request, executed)
            val current = assertNotNull(repository.beginAttempt(owner.handle))
            executed.forEach { it.extras[pageProgressAttemptKey]?.report(PageDownloadProgress.Failed) }
            assertEquals(PageDownloadProgress.Started, owner.progress.first())
            current.report(PageDownloadProgress.Idle)
        }
        repository.clear(owner.handle)
    }

    @Test
    fun untaggedSameUrlAndUnrelatedCoversCannotReportIntoAnOwnedPage() = runTest(dispatcher) {
        val repository = PageProgressRepositoryImpl()
        val owner = repository.observe(PAGE_URL)
        val attempt = assertNotNull(repository.beginAttempt(owner.handle))
        attempt.report(PageDownloadProgress.InProgress(0.35f))
        ProgressImages(repository, Interceptor { chain ->
            assertNull(chain.request.extras[pageProgressAttemptKey])
            chain.proceed()
        }).use { images ->
            listOf(PAGE_URL, "https://reader.test/cover.png").forEach { url ->
                val cover = ImageRequest.Builder(PlatformContext.INSTANCE).data(url).size(Size.ORIGINAL).build()
                assertIs<SuccessResult>(images.loader.execute(cover))
                assertEquals(PageDownloadProgress.InProgress(0.35f), owner.progress.first())
            }
        }
        repository.clear(owner.handle)
    }

    @Test
    fun cancellationRetiresTheCapturedExecutionBeforeTheSameUrlIsReowned() = runTest(dispatcher) {
        val repository = PageProgressRepositoryImpl()
        val owner = repository.observe(PAGE_URL)
        val entered = CompletableDeferred<PageProgressAttempt>()
        ProgressImages(repository, Interceptor { chain ->
            entered.complete(assertNotNull(chain.request.extras[pageProgressAttemptKey]))
            awaitCancellation()
        }).use { images ->
            val loading = async { images.loader.execute(pageRequest(owner.handle)) }
            val cancelledAttempt = entered.await()
            assertEquals(PageDownloadProgress.Started, owner.progress.first())
            loading.cancelAndJoin()
            assertEquals(PageDownloadProgress.Idle, owner.progress.first())
            repository.clear(owner.handle)
            val replacement = repository.observe(PAGE_URL)
            cancelledAttempt.report(PageDownloadProgress.InProgress(1f))
            cancelledAttempt.report(PageDownloadProgress.Complete)
            assertEquals(PageDownloadProgress.Idle, replacement.progress.first())
            repository.clear(replacement.handle)
        }
    }

    private fun pageRequest(handle: PageProgressHandle): ImageRequest =
        ImageRequest.Builder(PlatformContext.INSTANCE)
            .data(PAGE_URL)
            .size(Size.ORIGINAL)
            .memoryCacheKey("stable-page-key")
            .diskCacheKey("stable-disk-key")
            .httpHeaders(NetworkHeaders.Builder().set("Referer", "https://source.test/").build())
            .pageProgressHandle(handle)
            .build()

    private fun assertExecutionIdentities(original: ImageRequest, executed: List<ImageRequest>) {
        assertEquals(3, executed.size)
        assertNull(original.extras[pageProgressAttemptKey], "the remembered request must never capture an attempt")
        val attempts = executed.map { assertNotNull(it.extras[pageProgressAttemptKey]) }
        assertNotSame(attempts[0], attempts[1])
        assertNotSame(attempts[1], attempts[2])
        executed.forEach {
            assertSame(original.pageProgressHandle, it.pageProgressHandle)
            assertEquals(original.data, it.data)
            assertEquals(original.memoryCacheKey, it.memoryCacheKey)
            assertEquals(original.diskCacheKey, it.diskCacheKey)
            assertEquals(original.memoryCacheKeyExtras, it.memoryCacheKeyExtras)
            assertEquals(original.httpHeaders, it.httpHeaders)
        }
    }
}

private class ProgressImages(repository: PageProgressRepositoryImpl, downstream: Interceptor) : Closeable {
    private val bitmap = Bitmap().apply {
        check(allocN32Pixels(32, 32))
        erase(0xFF336699.toInt())
        setImmutable()
    }
    private val image = bitmap.asImage(shareable = true)
    var fetchCount = 0
        private set
    val loader = ImageLoader.Builder(PlatformContext.INSTANCE)
        .memoryCache { MemoryCache.Builder().maxSizeBytes(1024 * 1024L).build() }
        .diskCache(null)
        .components {
            add(PageProgressInterceptor(repository))
            add(downstream)
            add(Fetcher.Factory<Uri> { _, _, _ ->
                Fetcher {
                    fetchCount++
                    ImageFetchResult(image, isSampled = false, dataSource = DataSource.NETWORK)
                }
            })
        }.build()

    override fun close() {
        loader.shutdown()
        bitmap.close()
    }
}
