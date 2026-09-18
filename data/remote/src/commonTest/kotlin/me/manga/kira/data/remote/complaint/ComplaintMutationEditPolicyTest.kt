package me.manga.kira.data.remote.complaint

import io.ktor.http.Url
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import me.manga.kira.core.complaint.ComplaintMutationTransportPolicy as Policy

@Suppress("MagicNumber") // Contract header and request byte limits.
class ComplaintMutationEditPolicyTest {
    @Test
    fun editRouteBindsTheCanonicalNonV4TargetAndExactOriginPrefixAndContentSuffix() {
        val create = BASE + Policy.CREATE_PATH
        val edit = "$create/$MUTATION_TEST_PARENT/content"
        val other = "$create/$MUTATION_TEST_KEY/content"
        val target = assertNotNull(ComplaintMutationTarget.checked(Url(create)))
        assertEquals("/content", Policy.CONTENT_SUFFIX)
        assertEquals(ComplaintMutationRoute.EDIT, target.route(edit))
        assertEquals(MUTATION_TEST_PARENT, target.preconditionTargetId(edit))
        assertEquals("PATCH", ComplaintMutationRoute.EDIT.method)
        listOf(ComplaintMutationRoute.CREATE, ComplaintMutationRoute.REPLY, ComplaintMutationRoute.STATUS)
            .forEach { assertEquals("POST", it.method) }
        assertTrue(target.sameRoute(edit, edit))
        assertEquals(ComplaintMutationRoute.EDIT, target.route(other))
        assertFalse(target.sameRoute(edit, other))
        assertFalse(target.sameRoute(other, edit))
        assertFalse(target.sameRoute(edit, create))
        assertFalse(target.sameRoute(edit, replyPolicyUrl(create, MUTATION_TEST_PARENT)))
        assertFalse(target.sameRoute(edit, BASE + Policy.STATUS_PATH))
        invalidEditUrls(edit).forEach { value ->
            assertNull(target.route(value), value)
            assertNull(target.preconditionTargetId(value), value)
            assertFalse(target.sameRoute(edit, value), value)
        }
        assertNull(target.preconditionTargetId(create))
        assertNull(ComplaintMutationTarget.checked(Url(edit)), "Content is not an engine base.")
    }

    private fun invalidEditUrls(edit: String): List<String> =
        listOf(
            "$edit/",
            "$edit?",
            "$edit?version=1",
            "$edit#fragment",
            edit.replace("/content", "/Content"),
            edit.replace(MUTATION_TEST_PARENT, MUTATION_TEST_PARENT.uppercase()),
            edit.replace(MUTATION_TEST_PARENT, "%62" + MUTATION_TEST_PARENT.drop(1)),
            edit.replace(MUTATION_TEST_PARENT, "b-b-5-8-b"),
            edit.replace("/base_1/v2", ""),
            edit.replace(":9443", ""),
            edit.replace("example.invalid", "elsewhere.invalid"),
            edit.replace("https://", "http://"),
        )

    @Test
    fun editRequiresOneCanonicalStrongTargetBoundPositiveLongPrecondition() {
        val headers = mutationTestHeaders(ComplaintMutationRoute.EDIT)
        val withoutTag = headers.filterNot { it.first == "If-Match" }
        assertTrue(accepts(headers))
        assertTrue(accepts(withoutTag + ("If-Match" to "\"complaint-$MUTATION_TEST_PARENT-v${Long.MAX_VALUE}\"")))
        assertFalse(accepts(withoutTag))
        assertFalse(accepts(headers + ("if-match" to MUTATION_TEST_PRECONDITION)))
        listOf(
            "",
            "W/$MUTATION_TEST_PRECONDITION",
            "*",
            "$MUTATION_TEST_PRECONDITION, $MUTATION_TEST_PRECONDITION",
            "\"complaint-$MUTATION_TEST_KEY-v1\"",
            "\"complaint-${MUTATION_TEST_PARENT.uppercase()}-v1\"",
            "\"complaint-$MUTATION_TEST_PARENT-v0\"",
            "\"complaint-$MUTATION_TEST_PARENT-v01\"",
            "\"complaint-$MUTATION_TEST_PARENT-v-1\"",
            "\"complaint-$MUTATION_TEST_PARENT-v+1\"",
            "\"complaint-$MUTATION_TEST_PARENT-v9223372036854775808\"",
            "complaint-$MUTATION_TEST_PARENT-v1",
            " $MUTATION_TEST_PRECONDITION",
            "\t$MUTATION_TEST_PRECONDITION\t",
            "$MUTATION_TEST_PRECONDITION\u00a0",
            MUTATION_TEST_PRECONDITION + " ".repeat(256),
        ).forEach { value -> assertFalse(accepts(withoutTag + ("If-Match" to value))) }
        // Preparation canonicalizes backend-accepted SP/HTAB padding; native requests never emit it.
        listOf(null, MUTATION_TEST_KEY, MUTATION_TEST_PARENT.uppercase()).forEach { target ->
            assertFalse(accepts(headers, targetId = target))
        }
    }

    @Test
    fun editStillNeedsAV4KeyAndSixteenKiBBodyWhileOldPostRoutesForbidIfMatch() {
        val route = ComplaintMutationRoute.EDIT
        val headers = mutationTestHeaders(route)
        val withoutKey = headers.filterNot { it.first == Policy.IDEMPOTENCY_HEADER }
        assertFalse(accepts(withoutKey))
        assertFalse(accepts(headers + (Policy.IDEMPOTENCY_HEADER.lowercase() to MUTATION_TEST_KEY)))
        listOf(MUTATION_TEST_PARENT, MUTATION_TEST_KEY.uppercase(), "$MUTATION_TEST_KEY, $MUTATION_TEST_KEY")
            .forEach { key -> assertFalse(accepts(withoutKey + (Policy.IDEMPOTENCY_HEADER to key))) }
        assertEquals(16 * 1_024, Policy.MAX_REQUEST_BYTES)
        val cap = Policy.MAX_REQUEST_BYTES.toLong()
        assertTrue(accepts(headers + ("Content-Length" to "$cap"), cap))
        listOf(-1L, 0L, cap + 1).forEach { size -> assertFalse(accepts(headers, size)) }
        assertFalse(accepts(headers + ("Content-Length" to "2")))
        listOf("Cookie", "Proxy-Authorization", "Content-Encoding", "Transfer-Encoding").forEach { name ->
            assertFalse(accepts(headers + (name to "synthetic")))
        }
        listOf(ComplaintMutationRoute.CREATE, ComplaintMutationRoute.REPLY, ComplaintMutationRoute.STATUS)
            .forEach { oldRoute ->
                val oldHeaders = mutationTestHeaders(oldRoute)
                assertTrue(ComplaintMutationRequestHeaders.accepts(oldRoute, oldHeaders, 1))
                assertFalse(
                    ComplaintMutationRequestHeaders.accepts(
                        oldRoute,
                        oldHeaders + ("If-Match" to MUTATION_TEST_PRECONDITION),
                        1,
                        MUTATION_TEST_PARENT,
                    ),
                )
            }
    }

    private fun accepts(
        headers: List<Pair<String, String>>,
        bytes: Long = 1,
        targetId: String? = MUTATION_TEST_PARENT,
    ): Boolean = ComplaintMutationRequestHeaders.accepts(ComplaintMutationRoute.EDIT, headers, bytes, targetId)

    private companion object {
        const val BASE = "https://example.invalid:9443/base_1/v2"
    }
}
