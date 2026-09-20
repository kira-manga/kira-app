package me.manga.kira.data.remote.complaint

import io.ktor.http.Url
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import me.manga.kira.core.complaint.ComplaintMutationTransportPolicy as Policy

@Suppress("MagicNumber") // Closed HTTP framing and positive-Long precondition cells.
class ComplaintMutationOwnerDeletePolicyTest {
    @Test
    fun bareCanonicalNonV4TargetIsDeleteNotContentReplyCreateOrStatus() {
        val target = assertNotNull(ComplaintMutationTarget.checked(Url(CREATE)))
        assertEquals("DELETE", ComplaintMutationRoute.OWNER_DELETE.method)
        assertEquals(ComplaintMutationRoute.OWNER_DELETE, target.route(DELETE))
        assertEquals(MUTATION_TEST_PARENT, target.preconditionTargetId(DELETE))
        assertTrue(target.sameRoute(DELETE, DELETE))
        listOf(CREATE, "$DELETE/content", "$DELETE/replies", BASE + Policy.STATUS_PATH).forEach { other ->
            assertNotNull(target.route(other))
            assertFalse(target.sameRoute(DELETE, other))
            assertFalse(target.sameRoute(other, DELETE))
        }
        val other = "$CREATE/$MUTATION_TEST_KEY"
        assertEquals(ComplaintMutationRoute.OWNER_DELETE, target.route(other))
        assertFalse(target.sameRoute(DELETE, other))
        assertNull(ComplaintMutationTarget.checked(Url(DELETE)), "A target is not an engine base.")
    }

    @Test
    fun deleteTargetKeepsTheExactOriginDeploymentPrefixAndQuerylessCanonicalPath() {
        val target = assertNotNull(ComplaintMutationTarget.checked(Url(CREATE)))
        listOf(
            "$DELETE/",
            "$DELETE?",
            "$DELETE?version=1",
            "$DELETE#fragment",
            DELETE.replace(MUTATION_TEST_PARENT, MUTATION_TEST_PARENT.uppercase()),
            DELETE.replace(MUTATION_TEST_PARENT, "%62" + MUTATION_TEST_PARENT.drop(1)),
            DELETE.replace(MUTATION_TEST_PARENT, "b-b-5-8-b"),
            DELETE.replace("/base_1/v2", ""),
            DELETE.replace(":9443", ""),
            DELETE.replace("example.invalid", "elsewhere.invalid"),
            DELETE.replace("https://", "http://"),
        ).forEach { value ->
            assertNull(target.route(value), value)
            assertNull(target.preconditionTargetId(value), value)
            assertFalse(target.sameRoute(DELETE, value), value)
        }
    }

    @Test
    fun deleteRequestAllowsOnlyActualZeroBytesWithAbsentOrSingleCanonicalZeroLength() {
        val headers = mutationTestHeaders(ComplaintMutationRoute.OWNER_DELETE)
        assertTrue(accepts(headers))
        assertTrue(accepts(headers + ("Content-Length" to "0")))
        assertTrue(accepts(headers.map { it.first.lowercase() to it.second }))
        listOf(-1L, 1L, Policy.MAX_REQUEST_BYTES.toLong(), Long.MAX_VALUE).forEach { bytes ->
            assertFalse(accepts(headers, bytes))
        }
        listOf("", "00", " 0", "0 ", "+0", "-0", "0, 0", "1").forEach { length ->
            assertFalse(accepts(headers + ("Content-Length" to length)))
        }
        assertFalse(accepts(headers + listOf("Content-Length" to "0", "content-length" to "0")))
        listOf("", "application/json", "text/plain").forEach { media ->
            assertFalse(accepts(headers + ("Content-Type" to media)))
        }
        listOf("Content-Encoding" to "identity", "Content-Encoding" to "gzip", "Transfer-Encoding" to "chunked")
            .forEach { header -> assertFalse(accepts(headers + header)) }
    }

    @Test
    fun deleteRequiresOneV4KeyAndTheOriginalStrongTargetBoundTagIncludingLongMax() {
        val headers = mutationTestHeaders(ComplaintMutationRoute.OWNER_DELETE)
        val withoutKey = headers.filterNot { it.first == Policy.IDEMPOTENCY_HEADER }
        assertFalse(accepts(withoutKey))
        assertFalse(accepts(headers + (Policy.IDEMPOTENCY_HEADER.lowercase() to MUTATION_TEST_KEY)))
        listOf(MUTATION_TEST_PARENT, MUTATION_TEST_KEY.uppercase(), "$MUTATION_TEST_KEY, $MUTATION_TEST_KEY")
            .forEach { key -> assertFalse(accepts(withoutKey + (Policy.IDEMPOTENCY_HEADER to key))) }
        val withoutTag = headers.filterNot { it.first == "If-Match" }
        assertFalse(accepts(withoutTag))
        assertFalse(accepts(headers + ("if-match" to MUTATION_TEST_PRECONDITION)))
        assertTrue(accepts(withoutTag + ("If-Match" to "\"complaint-$MUTATION_TEST_PARENT-v${Long.MAX_VALUE}\"")))
        listOf(
            "W/$MUTATION_TEST_PRECONDITION",
            "*",
            "$MUTATION_TEST_PRECONDITION, $MUTATION_TEST_PRECONDITION",
            "\"complaint-$MUTATION_TEST_KEY-v1\"",
            "\"complaint-$MUTATION_TEST_PARENT-v0\"",
            "\"complaint-$MUTATION_TEST_PARENT-v01\"",
            "\"complaint-$MUTATION_TEST_PARENT-v9223372036854775808\"",
        ).forEach { tag -> assertFalse(accepts(withoutTag + ("If-Match" to tag))) }
        listOf(null, MUTATION_TEST_KEY, MUTATION_TEST_PARENT.uppercase()).forEach { target ->
            assertFalse(accepts(headers, target = target))
        }
    }

    @Test
    fun emptyDeleteDoesNotRelaxAnyExistingJsonRequestCell() {
        ComplaintMutationRoute.entries.filterNot { it == ComplaintMutationRoute.OWNER_DELETE }.forEach { route ->
            val headers = mutationTestHeaders(route)
            assertTrue(ComplaintMutationRequestHeaders.accepts(route, headers, 1, MUTATION_TEST_PARENT))
            assertFalse(ComplaintMutationRequestHeaders.accepts(route, headers, 0, MUTATION_TEST_PARENT))
            assertFalse(
                ComplaintMutationRequestHeaders.accepts(
                    route,
                    mutationTestHeaders(ComplaintMutationRoute.OWNER_DELETE),
                    0,
                    MUTATION_TEST_PARENT,
                ),
            )
        }
    }

    private fun accepts(
        headers: List<Pair<String, String>>,
        bytes: Long = 0,
        target: String? = MUTATION_TEST_PARENT,
    ): Boolean = ComplaintMutationRequestHeaders.accepts(ComplaintMutationRoute.OWNER_DELETE, headers, bytes, target)

    private companion object {
        const val BASE = "https://example.invalid:9443/base_1/v2"
        const val CREATE = BASE + Policy.CREATE_PATH
        const val DELETE = "$CREATE/$MUTATION_TEST_PARENT"
    }
}
