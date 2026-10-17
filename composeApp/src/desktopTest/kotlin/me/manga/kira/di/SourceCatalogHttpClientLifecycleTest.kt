package me.manga.kira.di

import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpRedirect
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
import kotlin.test.assertEquals
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
    fun actualFactoryKeepsDefaultsAndAllowsUncachedSingleHopClients() =
        runTest {
            withIsolatedHome { home ->
                val cache = File(home, ".kira-manga/cache/ktor_http_cache")
                for (followRedirects in listOf(true, false)) {
                    val uncached = createHttpClient(cacheResponses = false, followRedirects = followRedirects)
                    try {
                        assertNull(uncached.pluginOrNull(HttpCache))
                        assertEquals(followRedirects, uncached.pluginOrNull(HttpRedirect) != null)
                        assertFalse(cache.exists())
                    } finally {
                        closeAndJoin(uncached)
                    }
                }
                val cached = createHttpClient()
                try {
                    assertNotNull(cached.pluginOrNull(HttpCache))
                    assertNotNull(cached.pluginOrNull(HttpRedirect))
                    assertTrue(cache.isDirectory)
                } finally {
                    closeAndJoin(cached)
                }
            }
        }

    @Test
    fun catalogAndGenericTransportsHaveDistinctOwnedUncachedSingletons() =
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
            val generic = app.koin.get<HttpClient>(genericSourceHttpClientQualifier).also(clients::add)
            assertClientPolicies(shared, catalog, generic)
            assertSame(catalog, app.koin.get<HttpClient>(sourceCatalogHttpClientQualifier))
            assertSame(generic, app.koin.get<HttpClient>(genericSourceHttpClientQualifier))
            // Construction refuses cache/redirect plugins, so the other two clients cannot serve it.
            val executor = assertIs<KtorHttpExecutor>(app.koin.get<HttpExecutor>())
            assertSame(executor, app.koin.get<HttpExecutor>())
            assertIs<KtorRemoteSourceCatalog>(app.koin.get<RemoteSourceCatalog>())
            app.close()
            assertOwnedClientsClosed(catalog, generic)
            assertTrue(shared.coroutineContext.job.isActive, "source client ownership must not close the shared client")
        } finally {
            app.close()
            clients.forEach { closeAndJoin(it) }
        }
    }

    private fun assertClientPolicies(shared: HttpClient, catalog: HttpClient, generic: HttpClient) {
        assertNotSame(shared, catalog)
        assertNotSame(shared, generic)
        assertNotSame(catalog, generic)
        assertNotNull(shared.pluginOrNull(HttpCache))
        assertNotNull(shared.pluginOrNull(HttpRedirect))
        assertNull(catalog.pluginOrNull(HttpCache))
        assertNotNull(catalog.pluginOrNull(HttpRedirect))
        assertNull(generic.pluginOrNull(HttpCache))
        assertNull(generic.pluginOrNull(HttpRedirect))
    }

    private suspend fun assertOwnedClientsClosed(vararg clients: HttpClient) {
        for (client in clients) {
            withTimeout(CLIENT_CLEANUP_TIMEOUT_MILLIS) { client.coroutineContext.job.join() }
            assertTrue(client.coroutineContext.job.isCompleted)
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
