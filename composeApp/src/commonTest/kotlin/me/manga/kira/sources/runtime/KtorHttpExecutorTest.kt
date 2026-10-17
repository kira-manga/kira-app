package me.manga.kira.sources.runtime

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.request.HttpRequestData
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.cancel
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.SerializationException
import me.manga.kira.core.error.AppError
import me.manga.kira.core.result.AppResult
import me.manga.kira.domain.model.home.HomeFeedItem
import me.manga.kira.sources.contracts.SourceHttpMethod
import me.manga.kira.sources.contracts.SourceRequest
import me.manga.kira.sources.contracts.model.EndpointSpec
import me.manga.kira.sources.contracts.model.FieldSpec
import me.manga.kira.sources.contracts.model.SourceConfig
import me.manga.kira.sources.engine.GenericSourceClient
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class KtorHttpExecutorTest {
    @Test
    fun all_methods_preserve_payloads_source_headers_status_and_declared_charset() =
        runTest {
            for (method in SourceHttpMethod.entries) {
                withCatalogClient({ wire ->
                    assertRequest(method, wire)
                    respond(byteArrayOf(0xe9.toByte()), HttpStatusCode.ServiceUnavailable, latin1Headers())
                }, followRedirects = false) { client ->
                    val response = KtorHttpExecutor(client).execute(request(method))
                    assertEquals(503, response.status)
                    assertEquals("é", response.body)
                    assertEquals("first, second", response.headers["X-Values"])
                    assertEquals("/first, /second", response.headers[HttpHeaders.Location])
                }
            }
        }

    @Test
    fun absent_post_payloads_keep_empty_form_and_json_object_defaults() =
        runTest {
            for ((method, expected) in listOf(SourceHttpMethod.POST_FORM to "", SourceHttpMethod.POST_JSON to "{}")) {
                withCatalogClient({ wire ->
                    assertEquals(expected, wire.body.toByteArray().decodeToString())
                    respond("é") // No declared charset: bodyAsText's UTF-8 fallback is preserved.
                }, followRedirects = false) { client ->
                    assertEquals("é", KtorHttpExecutor(client).execute(SourceRequest(URL, method)).body)
                }
            }
        }

    @Test
    fun cached_clients_are_refused_before_any_request() =
        runTest {
            var requests = 0
            withCatalogClient({
                requests++
                respond("")
            }, cacheResponses = true, followRedirects = false) { client ->
                val failure = assertFailsWith<IllegalArgumentException> { KtorHttpExecutor(client) }
                assertEquals("generic sources require an uncached HTTP client", failure.message)
                assertEquals(0, requests)
            }
        }

    @Test
    fun redirecting_clients_are_refused_before_any_request() =
        runTest {
            var requests = 0
            withCatalogClient({
                requests++
                respond("")
            }) { client ->
                val failure = assertFailsWith<IllegalArgumentException> { KtorHttpExecutor(client) }
                assertEquals("generic sources require a single-hop HTTP client", failure.message)
                assertEquals(0, requests)
            }
        }

    @Test
    fun redirect_responses_are_one_hop_for_every_method() =
        runTest {
            for (method in SourceHttpMethod.entries) {
                for (status in listOf(301, 302, 303, 307, 308)) {
                    var requests = 0
                    val headers = locationHeaders("/next%2Cpart")
                    withCatalogClient({ wire ->
                        requests++
                        assertRequest(method, wire)
                        respond("redirect", HttpStatusCode.fromValue(status), headers)
                    }, followRedirects = false) { client ->
                        val response = KtorHttpExecutor(client).execute(request(method))
                        assertEquals(status, response.status)
                        assertEquals("redirect", response.body)
                        assertEquals("/next%2Cpart", response.headers[HttpHeaders.Location])
                        assertEquals(1, requests)
                    }
                }
            }
        }

    @Test
    fun shared_engine_preserves_finite_feeds_and_reselects_headers_across_redirects() =
        runTest {
            val requests = mutableListOf<HttpRequestData>()
            withCatalogClient({ wire ->
                requests += wire
                when (requests.size) {
                    1 -> respond("", HttpStatusCode.Found, locationHeaders("//foreign.test/step"))
                    2 -> respond("", HttpStatusCode.TemporaryRedirect, locationHeaders("https://images.test/final"))
                    else -> respond("""<div class="item"><a href="/manga/retry">Retry</a></div>""")
                }
            }, followRedirects = false) { client ->
                val source =
                    GenericSourceClient(
                        config(singlePage = true).copy(imageBase = "https://images.test", headers = mapOf("X-Static" to "static")),
                        KtorHttpExecutor(client),
                        NoopHeaderStore(mapOf(HttpHeaders.Cookie to "fixture=example", "X-Captured" to "captured")),
                    )
                val result = assertIs<AppResult.Success<List<HomeFeedItem>>>(source.home(1))
                assertEquals("Retry", result.value.single().title)
                assertEquals("https://source.test/manga/retry", result.value.single().url)
                val later = assertIs<AppResult.Success<List<HomeFeedItem>>>(source.home(2))
                assertTrue(later.value.isEmpty())
                assertRedirectHeaders(requests)
            }
        }

    @Test
    fun excessive_declared_length_rejects_without_reading_and_closes_open_bodies_for_all_methods() =
        runTest {
            for (method in SourceHttpMethod.entries) {
                val body = ByteChannel()
                try {
                    withCatalogClient(
                        { respond(body, headers = lengthHeaders(RESPONSE_LIMIT + 1)) },
                        followRedirects = false,
                    ) { client ->
                        assertFailsWith<SerializationException> { KtorHttpExecutor(client).execute(request(method)) }
                        assertTrue(body.isClosedForWrite, "early rejection must release the unread response")
                    }
                } finally {
                    body.cancel()
                }
            }
        }

    @Test
    fun oversize_and_protocol_failure_reach_typed_engine_errors_and_a_later_retry_succeeds() =
        runTest {
            for (oversize in listOf(true, false)) {
                val broken = ByteChannel().apply { cancel(IllegalStateException("fixture protocol failure")) }
                var requests = 0
                withCatalogClient({
                    requests++
                    when {
                        requests > 1 -> respond("""<div class="item"><a href="/manga/retry">Retry</a></div>""")
                        oversize -> respond("", headers = lengthHeaders(RESPONSE_LIMIT + 1))
                        else -> respond(broken)
                    }
                }, followRedirects = false) { client ->
                    assertFailedThenRetry(client, oversize)
                    assertEquals(2, requests)
                    assertTrue(broken.isClosedForRead)
                }
            }
        }

    private suspend fun assertRequest(
        method: SourceHttpMethod,
        wire: HttpRequestData,
    ) {
        assertEquals(URL, wire.url.toString())
        assertEquals("fixture", wire.headers["X-Source"])
        assertEquals("https://source.test/", wire.headers[HttpHeaders.Referrer])
        assertEquals(if (method == SourceHttpMethod.GET) HttpMethod.Get else HttpMethod.Post, wire.method)
        val expectedBody =
            when (method) {
                SourceHttpMethod.GET -> ""
                SourceHttpMethod.POST_FORM -> "genre%5B%5D=a%26b&genre%5B%5D=c+d"
                SourceHttpMethod.POST_JSON -> """{"query":"a&b"}"""
            }
        assertEquals(expectedBody, wire.body.toByteArray().decodeToString())
        if (method != SourceHttpMethod.GET) {
            val expectedType =
                if (method == SourceHttpMethod.POST_FORM) "application/x-www-form-urlencoded" else "application/json"
            assertEquals(expectedType, wire.body.contentType.toString().substringBefore(';'))
        }
    }

    private fun assertRedirectHeaders(requests: List<HttpRequestData>) {
        assertEquals(listOf(URL, "https://foreign.test/step", "https://images.test/final"), requests.map { it.url.toString() })
        val headers = mapOf("X-Static" to "static", HttpHeaders.Cookie to "fixture=example", "X-Captured" to "captured")
        requests.forEachIndexed { index, request ->
            headers.forEach { (name, value) ->
                if (index == 1) assertNull(request.headers[name]) else assertEquals(value, request.headers[name])
            }
        }
    }

    private suspend fun assertFailedThenRetry(
        client: HttpClient,
        oversize: Boolean,
    ) {
        val source = GenericSourceClient(config(), KtorHttpExecutor(client), NoopHeaderStore())
        val failure = assertIs<AppResult.Failure>(source.home(1))
        if (oversize) {
            assertIs<AppError.Network.Serialization>(failure.error)
        } else {
            assertIs<AppError.Unexpected>(failure.error)
        }
        val retry = assertIs<AppResult.Success<List<HomeFeedItem>>>(source.home(1))
        assertEquals("Retry", retry.value.single().title)
        assertEquals("https://source.test/manga/retry", retry.value.single().url)
    }

    private fun request(method: SourceHttpMethod) =
        SourceRequest(
            url = URL,
            method = method,
            headers = mapOf("X-Source" to "fixture", HttpHeaders.Referrer to "https://source.test/"),
            formBody = listOf("genre[]" to "a&b", "genre[]" to "c d"),
            jsonBody = """{"query":"a&b"}""",
        )

    private fun config(singlePage: Boolean = false) =
        SourceConfig(
            api = "fixture",
            language = "en",
            baseUrl = "https://source.test",
            engine = "generic",
            endpoints = mapOf("home" to EndpointSpec(URL, listSelector = ".item", singlePage = singlePage)),
            fields =
                mapOf(
                    "item.title" to FieldSpec(selector = "a"),
                    "item.url" to FieldSpec(selector = "a", attr = "href"),
                ),
        )

    private fun latin1Headers() =
        Headers.build {
            append(HttpHeaders.ContentType, "text/html; charset=ISO-8859-1")
            append("X-Values", "first")
            append("X-Values", "second")
            append(HttpHeaders.Location, "/first")
            append(HttpHeaders.Location, "/second")
        }

    private fun lengthHeaders(length: Int) = Headers.build { append(HttpHeaders.ContentLength, length.toString()) }

    private fun locationHeaders(value: String) = Headers.build { append(HttpHeaders.Location, value) }

    private companion object {
        const val RESPONSE_LIMIT = 4 * 1024 * 1024
        const val URL = "https://source.test/latest"
    }
}
