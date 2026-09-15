package me.manga.kira.di

import io.ktor.client.HttpClient
import io.ktor.client.plugins.cache.HttpCache
import io.ktor.client.plugins.pluginOrNull
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.job
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import me.manga.kira.data.remote.di.remoteModule
import me.manga.kira.data.remote.ktor.createHttpClient
import me.manga.kira.sources.contracts.HttpExecutor
import me.manga.kira.sources.contracts.RemoteSourceCatalog
import me.manga.kira.sources.runtime.KtorHttpExecutor
import me.manga.kira.sources.runtime.KtorRemoteSourceCatalog
import org.koin.dsl.koinApplication
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

private const val CLIENT_CLEANUP_TIMEOUT_MILLIS = 5_000L

/** Real Desktop factory and DI ownership, with no requests or native-networking claim. */
class SourceCatalogHttpClientLifecycleTest {
    @Test
    fun actualFactoryKeepsDefaultCacheButOptOutCreatesNoCacheDirectory() =
        runTest {
            withIsolatedHome { home ->
                val cache = File(home, ".kira-manga/cache/ktor_http_cache")
                val uncached = createHttpClient(cacheResponses = false)
                try {
                    assertNull(uncached.pluginOrNull(HttpCache))
                    assertFalse(cache.exists())
                } finally {
                    closeAndJoin(uncached)
                }
                val cached = createHttpClient()
                try {
                    assertNotNull(cached.pluginOrNull(HttpCache))
                    assertTrue(cache.isDirectory)
                } finally {
                    closeAndJoin(cached)
                }
            }
        }

    @Test
    fun boundedSourceTransportsUseAnUncachedSingletonClosedByKoin() =
        runTest {
            withIsolatedHome { assertCatalogClientLifecycle() }
        }

    private suspend fun assertCatalogClientLifecycle() {
        val clients = mutableListOf<HttpClient>()
        val app =
            koinApplication {
                allowOverride(false)
                modules(remoteModule(), sourcesGenericModule)
            }
        try {
            val shared = app.koin.get<HttpClient>().also(clients::add)
            val catalog = app.koin.get<HttpClient>(sourceCatalogHttpClientQualifier).also(clients::add)
            assertNotSame(shared, catalog)
            assertSame(catalog, app.koin.get<HttpClient>(sourceCatalogHttpClientQualifier))
            assertNotNull(shared.pluginOrNull(HttpCache))
            assertNull(catalog.pluginOrNull(HttpCache))
            // Construction refuses HttpCache, so resolving against the shared client must fail.
            val executor = assertIs<KtorHttpExecutor>(app.koin.get<HttpExecutor>())
            assertSame(executor, app.koin.get<HttpExecutor>())
            assertIs<KtorRemoteSourceCatalog>(app.koin.get<RemoteSourceCatalog>())
            app.close()
            withTimeout(CLIENT_CLEANUP_TIMEOUT_MILLIS) { catalog.coroutineContext.job.join() }
            assertTrue(catalog.coroutineContext.job.isCompleted)
            assertTrue(shared.coroutineContext.job.isActive, "catalog ownership must not close the shared client")
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
        val home = Files.createTempDirectory("kira-catalog-client-test").toFile()
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
