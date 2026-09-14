package me.manga.kira.data.remote.ktor

import io.ktor.client.HttpClient
import io.ktor.client.plugins.cache.HttpCache
import io.ktor.client.plugins.pluginOrNull
import io.ktor.util.date.GMTDate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.job
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import me.manga.kira.core.cache.HttpCacheClearer
import me.manga.kira.data.remote.di.remoteModule
import me.manga.kira.data.remote.ktor.cache.ManagedHttpCache
import me.manga.kira.data.remote.ktor.cache.cachedResponse
import org.koin.core.qualifier.named
import org.koin.dsl.koinApplication
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

private const val CLIENT_CLEANUP_TIMEOUT_MILLIS = 5_000L

/** Actual factory/DI ownership on the JVM; not a native-network or physical-device claim. */
class RemoteHttpClientOwnershipTest {
    @Test
    fun uncachedActualFactoryHasNeitherPluginOwnerNorCacheDirectory() =
        runTest {
            withIsolatedHome { home ->
                val client = createHttpClient(cacheResponses = false)
                try {
                    assertNull(client.pluginOrNull(HttpCache))
                    assertFailsWith<IllegalStateException> { client.responseCacheClearer() }
                    assertFalse(File(home, ".kira-manga/cache/ktor_http_cache").exists())
                } finally {
                    closeAndJoin(client)
                }
            }
        }

    @Test
    fun cachedActualFactoryAttachesTheLiveAndDiskOwnerUsedByTheClearPort() =
        runTest {
            withIsolatedHome { home ->
                val client = createHttpClient()
                try {
                    val directory = File(home, ".kira-manga/cache/ktor_http_cache")
                    assertNotNull(client.pluginOrNull(HttpCache))
                    assertTrue(directory.isDirectory)
                    val cache = assertIs<ManagedHttpCache>(client.responseCacheClearer())
                    val data = cachedResponse(expires = GMTDate().timestamp + 60_000)
                    cache.publicStorage.store(data.url, data)
                    assertNotNull(cache.publicStorage.find(data.url, emptyMap()))
                    assertEquals(1, directory.walkTopDown().count { it.isFile })
                    client.responseCacheClearer().clear()
                    assertNull(cache.publicStorage.find(data.url, emptyMap()))
                    assertEquals(0, directory.walkTopDown().count { it.isFile })
                } finally {
                    closeAndJoin(client)
                }
            }
        }

    @Test
    fun productionDownloadClientIsDistinctUncachedSingletonAndClosedByItsKoinOwner() =
        runTest {
            withIsolatedHome { verifyRemoteOwnership() }
        }

    private suspend fun verifyRemoteOwnership() {
        val clients = mutableListOf<HttpClient>()
        val app = koinApplication { modules(remoteModule()) }
        try {
            val shared = app.koin.get<HttpClient>().also(clients::add)
            val download = app.koin.get<HttpClient>(named("chapter-download-http")).also(clients::add)
            assertNotSame(shared, download)
            assertSame(download, app.koin.get<HttpClient>(named("chapter-download-http")))
            assertNull(download.pluginOrNull(HttpCache))
            assertNotNull(shared.pluginOrNull(HttpCache))
            assertSame(shared.responseCacheClearer(), app.koin.get<HttpCacheClearer>())
            app.close()
            withTimeout(CLIENT_CLEANUP_TIMEOUT_MILLIS) { download.coroutineContext.job.join() }
            assertTrue(download.coroutineContext.job.isCompleted)
            assertTrue(
                shared.coroutineContext.job.isActive,
                "download ownership must not change shared client lifetime",
            )
        } finally {
            app.close()
            clients.forEach { closeAndJoin(it) }
        }
    }

    private suspend fun closeAndJoin(client: HttpClient) {
        client.close()
        withContext(NonCancellable + Dispatchers.Default) {
            withTimeout(CLIENT_CLEANUP_TIMEOUT_MILLIS) {
                client.coroutineContext.job.cancelAndJoin()
                client.engine.coroutineContext.job
                    .cancelAndJoin()
            }
        }
    }

    private suspend fun withIsolatedHome(block: suspend (File) -> Unit) {
        val home = Files.createTempDirectory("kira-remote-client-test").toFile()
        val previous = System.getProperty("user.home")
        System.setProperty("user.home", home.absolutePath)
        try {
            block(home)
        } finally {
            if (previous == null) System.clearProperty("user.home") else System.setProperty("user.home", previous)
            check(home.deleteRecursively())
        }
    }
}
