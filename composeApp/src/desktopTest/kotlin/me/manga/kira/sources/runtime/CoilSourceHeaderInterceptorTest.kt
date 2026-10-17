package me.manga.kira.sources.runtime

import coil3.ImageLoader
import coil3.PlatformContext
import coil3.intercept.Interceptor
import coil3.network.httpHeaders
import coil3.request.ErrorResult
import coil3.request.ImageRequest
import coil3.size.Size
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalCoroutinesApi::class)
class CoilSourceHeaderInterceptorTest {
    private val dispatcher = StandardTestDispatcher()

    @BeforeTest
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun authoritative_empty_headers_cannot_inherit_another_sources_shared_host_credentials() = runTest(dispatcher) {
        var hostReads = 0
        val observed = mutableListOf<Map<String, String>>()
        val loader = ImageLoader.Builder(PlatformContext.INSTANCE).diskCache(null).components {
            add(CoilSourceHeaderInterceptor { host ->
                assertEquals("shared.test", host)
                hostReads++
                // Simulates the older source owning the same host as a capture-free source.
                mapOf("Cookie" to "other-source-fixture")
            })
            add(Interceptor { chain ->
                observed += chain.request.httpHeaders.asMap().mapValues { it.value.joinToString(", ") }
                // Terminate at the real interceptor boundary: no HTTP, image decode or UI automation.
                ErrorResult(null, chain.request, IllegalStateException("fixture end"))
            })
        }.build()
        try {
            val request = ImageRequest.Builder(PlatformContext.INSTANCE).data("https://shared.test/page.webp")
                .size(Size.ORIGINAL).build()
            loader.execute(request.newBuilder().resolvedSourceHeaders(emptyMap()).build())
            assertEquals(0, hostReads)
            assertEquals(emptyMap(), observed.last())
            loader.execute(request.newBuilder().resolvedSourceHeaders(mapOf("Referer" to "explicit")).build())
            assertEquals(0, hostReads)
            assertEquals(mapOf("referer" to "explicit"), observed.last())
            loader.execute(request)
            assertEquals(1, hostReads)
            assertEquals(mapOf("cookie" to "other-source-fixture"), observed.last())
        } finally {
            loader.shutdown()
        }
    }
}
