package me.manga.kira.data.remote.complaint

import kotlin.test.Test
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
        if (route == ComplaintMutationRoute.CREATE) {
            listOf(Policy.IDEMPOTENCY_HEADER to MUTATION_TEST_KEY)
        } else {
            emptyList()
        }

internal const val MUTATION_TEST_KEY = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
internal const val MUTATION_TEST_AUTHORIZATION = "Bearer synthetic.header.signature"
