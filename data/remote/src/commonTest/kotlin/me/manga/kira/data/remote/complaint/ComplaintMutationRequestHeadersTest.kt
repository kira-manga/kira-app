package me.manga.kira.data.remote.complaint

import io.ktor.client.engine.mergeHeaders
import io.ktor.client.utils.buildHeaders
import io.ktor.http.ContentType
import io.ktor.http.content.ByteArrayContent
import io.ktor.utils.io.InternalAPI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import me.manga.kira.core.complaint.ComplaintMutationTransportPolicy as Policy

class ComplaintMutationRequestHeadersTest {
    @Test
    fun createRequiresExactlyOneCanonicalKeyButStatusForbidsIt() {
        val create = mutationTestHeaders(ComplaintMutationRoute.CREATE)
        val status = mutationTestHeaders(ComplaintMutationRoute.STATUS)
        assertTrue(accepts(ComplaintMutationRoute.CREATE, create))
        assertTrue(accepts(ComplaintMutationRoute.STATUS, status))
        assertFalse(accepts(ComplaintMutationRoute.CREATE, status))
        assertFalse(accepts(ComplaintMutationRoute.STATUS, create))
        listOf(
            MUTATION_TEST_KEY.uppercase(),
            MUTATION_TEST_KEY.replace("-4", "-1"),
            MUTATION_TEST_KEY.replace("-8", "-7"),
            " $MUTATION_TEST_KEY",
            "$MUTATION_TEST_KEY, $MUTATION_TEST_KEY",
            "",
        ).forEach { key ->
            assertFalse(accepts(ComplaintMutationRoute.CREATE, status + (Policy.IDEMPOTENCY_HEADER to key)))
        }
        assertFalse(accepts(ComplaintMutationRoute.CREATE, create + (Policy.IDEMPOTENCY_HEADER to MUTATION_TEST_KEY)))
    }

    @Test
    fun fixedRequestHeadersForbidPreconditionsCodingCredentialsAndAmbiguity() {
        val route = ComplaintMutationRoute.CREATE
        val headers = mutationTestHeaders(route)
        listOf(
            "If-Match",
            "Cookie",
            "Cookie2",
            "Proxy-Authorization",
            "Content-Encoding",
            "Transfer-Encoding",
            "X-Other",
        ).forEach { name -> assertFalse(accepts(route, headers + (name to "synthetic"))) }
        headers.forEach { header -> assertFalse(accepts(route, headers + header)) }
        listOf("Authorization", "Accept-Encoding", "Content-Type").forEach { name ->
            assertFalse(accepts(route, headers.filterNot { it.first == name }))
        }
        assertTrue(accepts(route, headers.map { it.first.lowercase() to it.second }))
    }

    @Test
    fun onlyKnownBoundedBodiesAndTruthfulSingleLengthAreAccepted() {
        val route = ComplaintMutationRoute.CREATE
        val headers = mutationTestHeaders(route)
        val cap = Policy.MAX_REQUEST_BYTES.toLong()
        assertTrue(ComplaintMutationRequestHeaders.accepts(route, headers, cap))
        assertTrue(ComplaintMutationRequestHeaders.accepts(route, headers + ("Content-Length" to "$cap"), cap))
        listOf(-1L, 0L, cap + 1).forEach { size ->
            assertFalse(ComplaintMutationRequestHeaders.accepts(route, headers, size))
        }
        listOf("0", "01", "2", "1, 1").forEach { length ->
            assertFalse(accepts(route, headers + ("Content-Length" to length)))
        }
        assertTrue(accepts(route, headers + ("Content-Length" to "1")))
    }

    @Test
    fun onlyOptionalSingleFixedSupplierUserAgentFitsTheClosedNativeHeaderSet() {
        ComplaintMutationRoute.entries.forEach { route ->
            val applicationHeaders = mutationTestHeaders(route) + ("Content-Length" to "1")
            val nativeHeaders = mutationTestEngineHeaders(route)
            assertEquals(applicationHeaders.toSet() + ("User-Agent" to "ktor-client"), nativeHeaders.toSet())
            assertEquals(applicationHeaders.size + 1, nativeHeaders.size)
            assertTrue(accepts(route, applicationHeaders))
            assertTrue(accepts(route, nativeHeaders))
            assertTrue(accepts(route, nativeHeaders.map { it.first.lowercase() to it.second }))
            listOf("", "synthetic", "Ktor-client", " ktor-client", "ktor-client ", "ktor-client,ktor-client")
                .forEach { value ->
                    assertFalse(accepts(route, applicationHeaders + ("User-Agent" to value)))
                }
            assertFalse(accepts(route, nativeHeaders + ("user-agent" to "ktor-client")))
            assertFalse(accepts(route, nativeHeaders + ("X-Other" to "synthetic")))
            applicationHeaders.forEach { header -> assertFalse(accepts(route, nativeHeaders + header)) }
            val other =
                if (route != ComplaintMutationRoute.STATUS) {
                    ComplaintMutationRoute.STATUS
                } else {
                    ComplaintMutationRoute.CREATE
                }
            assertFalse(accepts(other, nativeHeaders))
        }
    }

    private fun accepts(
        route: ComplaintMutationRoute,
        headers: List<Pair<String, String>>,
    ): Boolean = ComplaintMutationRequestHeaders.accepts(route, headers, 1)
}

internal fun mutationTestHeaders(route: ComplaintMutationRoute): List<Pair<String, String>> =
    listOf(
        "Authorization" to MUTATION_TEST_AUTHORIZATION,
        "Accept" to "application/json, application/problem+json",
        "Accept-Encoding" to "identity",
        "Cache-Control" to "no-store, no-transform",
        "Content-Type" to "application/json",
    ) +
        if (route != ComplaintMutationRoute.STATUS) {
            listOf(Policy.IDEMPOTENCY_HEADER to MUTATION_TEST_KEY)
        } else {
            emptyList()
        }

/** Executes the same supplier header merger used by both Ktor native converters; no HTTP claim. */
@OptIn(InternalAPI::class)
internal fun mutationTestEngineHeaders(route: ComplaintMutationRoute): List<Pair<String, String>> =
    buildList {
        val headers =
            buildHeaders {
                mutationTestHeaders(route).filterNot { it.first == "Content-Type" }.forEach { (name, value) ->
                    append(name, value)
                }
            }
        val body = ByteArrayContent(byteArrayOf('x'.code.toByte()), ContentType.Application.Json)
        mergeHeaders(headers, body) { name, value -> add(name to value) }
    }

internal const val MUTATION_TEST_KEY = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
internal const val MUTATION_TEST_PARENT = "bbbbbbbb-bbbb-5bbb-8bbb-bbbbbbbbbbbb"
internal const val MUTATION_TEST_AUTHORIZATION = "Bearer synthetic.header.signature"
