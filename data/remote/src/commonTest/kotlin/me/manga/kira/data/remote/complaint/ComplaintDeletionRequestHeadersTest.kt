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
import me.manga.kira.core.complaint.ComplaintDeletionTransportPolicy as Policy

class ComplaintDeletionRequestHeadersTest {
    @Test
    fun bodyAuthenticationForbidsBearerCookiesAndAnyUnspecifiedHeader() {
        val headers = deletionTestHeaders()
        assertTrue(accepts(headers))
        listOf("Authorization", "Cookie", "Cookie2", "Proxy-Authorization", "If-Match", "X-Other")
            .forEach { assertFalse(accepts(headers + (it to "synthetic"))) }
        headers.forEach { header ->
            assertFalse(accepts(headers + header))
            assertFalse(accepts(headers - header))
        }
        assertTrue(accepts(headers.map { it.first.lowercase() to it.second }))
    }

    @Test
    fun exactlyOneCanonicalKeyAndIdentityJsonFramingAreRequired() {
        val headers = deletionTestHeaders()
        val withoutKey = headers.filterNot { it.first == Policy.IDEMPOTENCY_HEADER }
        listOf("", DELETION_TEST_KEY.uppercase(), " $DELETION_TEST_KEY", "$DELETION_TEST_KEY,$DELETION_TEST_KEY")
            .forEach { assertFalse(accepts(withoutKey + (Policy.IDEMPOTENCY_HEADER to it))) }
        listOf("Content-Encoding" to "gzip", "Transfer-Encoding" to "chunked", "Accept-Encoding" to "gzip")
            .forEach { assertFalse(accepts(headers + it)) }
        assertFalse(accepts(headers.map { if (it.first == "Content-Type") it.first to "text/plain" else it }))
    }

    @Test
    fun actualFourKibLimitAndTruthfulOptionalLengthCannotUseTheMutationBudget() {
        val headers = deletionTestHeaders()
        val maximum = Policy.MAX_REQUEST_BYTES.toLong()
        assertTrue(ComplaintDeletionRequestHeaders.accepts(headers, maximum))
        assertTrue(ComplaintDeletionRequestHeaders.accepts(headers + ("Content-Length" to "$maximum"), maximum))
        listOf(-1L, 0L, maximum + 1).forEach { assertFalse(ComplaintDeletionRequestHeaders.accepts(headers, it)) }
        listOf("0", "01", "2", "1,1").forEach { assertFalse(accepts(headers + ("Content-Length" to it))) }
        assertTrue(accepts(headers + ("Content-Length" to "1")))
    }

    @Test
    fun pinnedKtorHeaderMergeAddsOnlySingleFixedUserAgentAndLength() {
        val native = deletionTestEngineHeaders()
        val expected = deletionTestHeaders() + ("Content-Length" to "1") + ("User-Agent" to "ktor-client")
        assertEquals(expected.toSet(), native.toSet())
        assertEquals(expected.size, native.size)
        assertTrue(accepts(native))
        listOf("", "synthetic", "Ktor-client", "ktor-client,ktor-client", " ktor-client")
            .forEach { assertFalse(accepts(deletionTestHeaders() + ("User-Agent" to it))) }
        assertFalse(accepts(native + ("user-agent" to "ktor-client")))
    }

    private fun accepts(headers: List<Pair<String, String>>): Boolean = ComplaintDeletionRequestHeaders.accepts(headers, 1)
}

internal fun deletionTestHeaders(): List<Pair<String, String>> =
    listOf(
        "Accept" to "application/json, application/problem+json",
        "Accept-Encoding" to "identity",
        "Cache-Control" to "no-store, no-transform",
        "Content-Type" to "application/json",
        Policy.IDEMPOTENCY_HEADER to DELETION_TEST_KEY,
    )

/** Supplier merge only, not a request or native-execution receipt. */
@OptIn(InternalAPI::class)
internal fun deletionTestEngineHeaders(): List<Pair<String, String>> =
    buildList {
        val headers =
            buildHeaders {
                deletionTestHeaders().filterNot { it.first == "Content-Type" }.forEach { (name, value) ->
                    append(name, value)
                }
            }
        val body = ByteArrayContent(byteArrayOf('x'.code.toByte()), ContentType.Application.Json)
        mergeHeaders(headers, body) { name, value -> add(name to value) }
    }

internal const val DELETION_TEST_KEY = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
