package me.manga.kira.data.remote.complaint

import io.ktor.http.Url
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import me.manga.kira.core.complaint.ComplaintMutationTransportPolicy as Policy

class ComplaintMutationReplyPolicyTest {
    @Test
    fun replyTargetBindsTheExactCanonicalParentNotJustTheSharedRouteEnum() {
        val create = BASE + Policy.CREATE_PATH
        val target = assertNotNull(ComplaintMutationTarget.checked(Url(create)))
        val first = replyPolicyUrl(create)
        val second = replyPolicyUrl(create, REPLY_POLICY_NOTICE)
        assertEquals(ComplaintMutationRoute.REPLY, target.route(first))
        assertEquals(ComplaintMutationRoute.REPLY, target.route(second))
        assertTrue(target.sameRoute(first, first))
        assertTrue(target.sameRoute(second, second))
        assertFalse(target.sameRoute(first, second))
        assertFalse(target.sameRoute(second, first))
        assertFalse(target.sameRoute(first, create))
        assertFalse(target.sameRoute(first, BASE + Policy.STATUS_PATH))
        val invalid =
            listOf(
                "$first/",
                "$first?",
                "$first?key=private",
                first.replace(REPLY_POLICY_PARENT, REPLY_POLICY_PARENT.uppercase()),
                first.replace(REPLY_POLICY_PARENT, "a-a-4-8-a"),
                first.replace(REPLY_POLICY_PARENT, "%61" + REPLY_POLICY_PARENT.drop(1)),
                first.removeSuffix("/replies"),
                first.replace("/base_1/v2", ""),
            )
        invalid.forEach { assertNull(target.route(it), it) }
        assertNull(ComplaintMutationTarget.checked(Url(first)), "A parent route cannot become a new engine base.")
    }

    @Test
    fun replyRequiresOneCanonicalKeyAndTheSameBoundedCredentialSafeNativeHeaders() {
        val route = ComplaintMutationRoute.REPLY
        val headers = mutationTestEngineHeaders(route)
        assertTrue(ComplaintMutationRequestHeaders.accepts(route, headers, 1))
        assertEquals(
            listOf(MUTATION_TEST_KEY),
            headers.filter { it.first == Policy.IDEMPOTENCY_HEADER }.map { it.second },
        )
        assertFalse(ComplaintMutationRequestHeaders.accepts(ComplaintMutationRoute.STATUS, headers, 1))
        val withoutKey = headers.filterNot { it.first.equals(Policy.IDEMPOTENCY_HEADER, ignoreCase = true) }
        assertFalse(ComplaintMutationRequestHeaders.accepts(route, withoutKey, 1))
        assertFalse(
            ComplaintMutationRequestHeaders.accepts(
                route,
                headers + (Policy.IDEMPOTENCY_HEADER to MUTATION_TEST_KEY),
                1,
            ),
        )
        assertFalse(
            ComplaintMutationRequestHeaders.accepts(
                route,
                withoutKey + (Policy.IDEMPOTENCY_HEADER to REPLY_POLICY_NOTICE),
                1,
            ),
        )
        for (name in listOf("If-Match", "Cookie", "Proxy-Authorization", "Content-Encoding")) {
            assertFalse(ComplaintMutationRequestHeaders.accepts(route, headers + (name to "synthetic"), 1))
        }
        val applicationHeaders = mutationTestHeaders(route)
        assertTrue(
            ComplaintMutationRequestHeaders.accepts(route, applicationHeaders, Policy.MAX_REQUEST_BYTES.toLong()),
        )
        assertFalse(
            ComplaintMutationRequestHeaders.accepts(route, applicationHeaders, Policy.MAX_REQUEST_BYTES.toLong() + 1),
        )
    }

    @Test
    fun onlyReply201JsonGetsThirtyTwoKibibytesWhileParentAndPendingProblemsStaySixteen() {
        val large = Policy.MAX_CREATE_ACKNOWLEDGEMENT_BYTES
        val small = Policy.MAX_STATUS_OR_PROBLEM_BYTES
        val acknowledgement =
            assertNotNull(
                ComplaintMutationReceiveBudget.checked(
                    ComplaintMutationRoute.REPLY,
                    201L,
                    headers(length = listOf(large.toString())),
                ),
            )
        assertEquals(large, acknowledgement.remainingBytes)
        assertTrue(acknowledgement.accept(large.toULong()))
        assertTrue(acknowledgement.isComplete())
        assertFalse(acknowledgement.accept(1uL))
        for (status in listOf(404L, 409L)) {
            val problem =
                assertNotNull(
                    ComplaintMutationReceiveBudget.checked(
                        ComplaintMutationRoute.REPLY,
                        status,
                        headers(media = listOf("application/problem+json")),
                    ),
                )
            assertEquals(small, problem.remainingBytes)
            assertTrue(problem.accept(small.toULong()))
            assertFalse(problem.accept(1uL))
            assertNull(
                ComplaintMutationReceiveBudget.checked(
                    ComplaintMutationRoute.REPLY,
                    status,
                    headers(media = listOf("application/problem+json"), length = listOf(large.toString())),
                ),
            )
        }
        assertNull(
            ComplaintMutationReceiveBudget.checked(
                ComplaintMutationRoute.REPLY,
                201L,
                headers(media = listOf("application/json", "application/json"), length = listOf(large.toString())),
            ),
        )
        assertNull(
            ComplaintMutationReceiveBudget.checked(
                ComplaintMutationRoute.REPLY,
                201L,
                headers(encoding = listOf("gzip")),
            ),
        )
    }

    private fun headers(
        media: List<String> = listOf("application/json"),
        encoding: List<String> = emptyList(),
        length: List<String> = emptyList(),
    ): ComplaintMutationResponseHeaders = ComplaintMutationResponseHeaders(media, encoding, length, emptyList())

    private companion object {
        const val BASE = "https://example.invalid:9443/base_1/v2"
    }
}

internal fun replyPolicyUrl(
    createUrl: String,
    parentId: String = REPLY_POLICY_PARENT,
): String = "$createUrl/$parentId/replies"

internal const val REPLY_POLICY_PARENT = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
internal const val REPLY_POLICY_NOTICE = "bbbbbbbb-bbbb-5bbb-8bbb-bbbbbbbbbbbb"
