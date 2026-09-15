package me.manga.kira.data.remote.ktor

import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.cache.HttpCache
import io.ktor.client.plugins.pluginOrNull
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import kotlinx.coroutines.test.runTest
import me.manga.kira.data.remote.ktor.cache.assertWithin
import me.manga.kira.data.remote.ktor.cache.cacheHeaders
import me.manga.kira.data.remote.ktor.cache.cacheOwner
import me.manga.kira.data.remote.ktor.cache.smallCachePolicy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame

class HttpCachePluginTest {
    @Test
    fun metadataIsReusedHeadersArePreservedAndActualOwnerClearForcesNetwork() =
        runTest {
            val cache = cacheOwner()
            val requestHeaders =
                mapOf(
                    HttpHeaders.UserAgent to "cache-policy-test",
                    HttpHeaders.Referrer to "https://reader.test/",
                    HttpHeaders.Cookie to "session=test-only",
                    HttpHeaders.Authorization to "Bearer test-only",
                )
            var calls = 0
            withHttpCacheClient(cache, { request ->
                calls++
                requestHeaders.forEach { (key, value) -> assertEquals(value, request.headers[key]) }
                respond("{\"call\":$calls}", headers = cacheHeaders())
            }) { client ->
                suspend fun fetch(): String =
                    client
                        .get("https://metadata.test/catalog") {
                            requestHeaders.forEach { (key, value) -> headers.append(key, value) }
                        }.bodyAsText()
                assertEquals("{\"call\":1}", fetch())
                assertEquals("{\"call\":1}", fetch())
                assertEquals(1, calls)
                assertSame(cache, client.responseCacheClearer())
                client.responseCacheClearer().clear()
                assertEquals("{\"call\":2}", fetch())
                assertEquals(2, calls)
            }
        }

    @Test
    fun manyMockResponsesStayWithinTheSharedMetadataBudget() =
        runTest {
            val policy = smallCachePolicy().copy(maxTotalBytes = 2_048)
            val cache = cacheOwner(policy)
            var calls = 0
            withHttpCacheClient(cache, {
                calls++
                respond("x".repeat(400), headers = cacheHeaders())
            }) { client ->
                repeat(40) { index ->
                    assertEquals(400, client.get("https://metadata.test/$index").bodyAsText().length)
                    cache.assertWithin(policy)
                }
                assertEquals(40, calls)
                client.get("https://metadata.test/39").bodyAsText()
                assertEquals(40, calls)
            }
        }

    @Test
    fun pluginDoesNotRetainHeaderlessNoStoreImageOrEventStreamBodies() =
        runTest {
            val cases =
                listOf(
                    cacheHeaders(null, null),
                    cacheHeaders(cacheControl = null),
                    cacheHeaders(cacheControl = "no-store, max-age=60"),
                    cacheHeaders("image/jpeg"),
                    cacheHeaders("text/event-stream"),
                )
            cases.forEach { headers ->
                val cache = cacheOwner()
                var calls = 0
                withHttpCacheClient(cache, {
                    calls++
                    respond("body", headers = headers)
                }) { client ->
                    repeat(2) { assertEquals("body", client.get("https://metadata.test/body").bodyAsText()) }
                    assertEquals(2, calls)
                    assertEquals(0, cache.snapshot().entries)
                }
            }
        }

    @Test
    fun privateAndPublicVaryResponsesCoexistWithoutOverwriting() =
        runTest {
            val cache = cacheOwner()
            var calls = 0
            withHttpCacheClient(cache, { request ->
                calls++
                val visibility = request.headers["X-Visibility"] ?: "public"
                val headers =
                    Headers.build {
                        appendAll(cacheHeaders(cacheControl = "$visibility, max-age=60"))
                        append(HttpHeaders.Vary, "X-Visibility")
                    }
                respond(visibility, headers = headers)
            }) { client ->
                repeat(2) {
                    listOf("public", "private").forEach { visibility ->
                        val body =
                            client
                                .get("https://metadata.test/vary") {
                                    headers.append("X-Visibility", visibility)
                                }.bodyAsText()
                        assertEquals(visibility, body)
                    }
                }
                assertEquals(2, calls)
                assertEquals(2, cache.snapshot().entries)
            }
        }

    @Test
    fun downloadStyleUncachedClientHasNoPluginOrOwnerAndNeverRetainsPageGets() =
        runTest {
            var calls = 0
            withHttpCacheClient(null, {
                calls++
                respond("page".repeat(512), headers = cacheHeaders("image/jpeg"))
            }) { client ->
                assertNull(client.pluginOrNull(HttpCache))
                assertFailsWith<IllegalStateException> { client.responseCacheClearer() }
                repeat(2) { assertEquals(2_048, client.get("https://pages.test/1.jpg").bodyAsText().length) }
                assertEquals(2, calls)
            }
        }
}
