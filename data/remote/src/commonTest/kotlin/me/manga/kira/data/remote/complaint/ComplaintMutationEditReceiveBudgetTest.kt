package me.manga.kira.data.remote.complaint

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import me.manga.kira.core.complaint.ComplaintMutationTransportPolicy as Policy

@Suppress("MagicNumber") // Closed HTTP cells and exact byte-boundary vectors.
class ComplaintMutationEditReceiveBudgetTest {
    @Test
    fun onlyEdit200WithSingleJsonMediaGetsTheNamedEditBudgetAndStatusNeverBorrowsIt() {
        assertEquals(32 * 1_024, Policy.MAX_EDIT_ACKNOWLEDGEMENT_BYTES)
        assertEquals(16 * 1_024, Policy.MAX_STATUS_OR_PROBLEM_BYTES)
        listOf("application/json", "APPLICATION/JSON; charset=\"UTF-8\"").forEach { media ->
            val edit = assertNotNull(budget(headers = responseHeaders(media = listOf(media))))
            assertIs<ComplaintMutationReceiveBudget>(edit)
            assertEquals(Policy.MAX_EDIT_ACKNOWLEDGEMENT_BYTES, edit.remainingBytes)
        }
        listOf(201L, 204L, 400L, 401L, 404L, 409L, 412L, 421L, 428L, 429L, 500L, 503L).forEach { status ->
            val edit = assertNotNull(budget(status = status))
            assertIs<ComplaintSessionReceiveBudget>(edit)
            assertEquals(Policy.MAX_STATUS_OR_PROBLEM_BYTES, edit.remainingBytes)
        }
        listOf(200L, 201L, 409L, 412L, 503L).forEach { status ->
            val response = assertNotNull(budget(route = ComplaintMutationRoute.STATUS, status = status))
            assertEquals(Policy.MAX_STATUS_OR_PROBLEM_BYTES, response.remainingBytes)
        }
        listOf(
            emptyList(),
            listOf("application/problem+json"),
            listOf("text/html"),
            listOf("application/json", "application/json"),
            listOf("application/json, application/json"),
            listOf("application/json; charset=iso-8859-1"),
        ).forEach { media ->
            val response = assertNotNull(budget(headers = responseHeaders(media = media)))
            assertIs<ComplaintSessionReceiveBudget>(response)
            assertEquals(Policy.MAX_STATUS_OR_PROBLEM_BYTES, response.remainingBytes)
        }
    }

    @Test
    fun declaredEditLimitRequiresExactEofAndRejectedBytesNeverAdvanceAccounting() {
        val cap = Policy.MAX_EDIT_ACKNOWLEDGEMENT_BYTES
        val edit = assertNotNull(budget(headers = responseHeaders(length = listOf("$cap"))))
        assertTrue(edit.accept((cap - 1).toULong()))
        assertFalse(edit.isComplete())
        assertTrue(edit.accept(1uL))
        assertTrue(edit.isComplete())
        assertFalse(edit.accept(1uL))
        assertFalse(edit.accept(ULong.MAX_VALUE))
        assertEquals(cap, edit.receivedBytes)
        assertEquals(0, edit.remainingBytes)
        assertNull(budget(headers = responseHeaders(length = listOf("${cap + 1}"))))
        assertNull(budget(status = 201, headers = responseHeaders(length = listOf("$cap"))))
        assertNull(budget(status = 412, headers = responseHeaders(length = listOf("$cap"))))
        assertNull(budget(route = ComplaintMutationRoute.STATUS, headers = responseHeaders(length = listOf("$cap"))))
        assertNull(
            budget(
                headers =
                    responseHeaders(media = listOf("application/json", "application/json"), length = listOf("$cap")),
            ),
        )
    }

    @Test
    fun editAcknowledgementKeepsIdentityEncodingAndSingleUnambiguousFraming() {
        listOf(
            responseHeaders(encoding = listOf("gzip")),
            responseHeaders(encoding = listOf("identity", "identity")),
            responseHeaders(length = listOf("1", "1")),
            responseHeaders(length = listOf("1, 1")),
            responseHeaders(length = listOf("+1")),
            responseHeaders(length = listOf("9223372036854775808")),
            responseHeaders(length = listOf("1"), transfer = listOf("chunked")),
            responseHeaders(transfer = listOf("gzip")),
            responseHeaders(transfer = listOf("chunked", "chunked")),
        ).forEach { assertNull(budget(headers = it)) }
        val chunked =
            assertNotNull(
                budget(headers = responseHeaders(encoding = listOf("identity"), transfer = listOf("chunked"))),
            )
        assertTrue(chunked.accept(Policy.MAX_EDIT_ACKNOWLEDGEMENT_BYTES.toULong()))
        assertFalse(chunked.accept(1uL))
    }

    private fun budget(
        route: ComplaintMutationRoute = ComplaintMutationRoute.EDIT,
        status: Long = 200,
        headers: ComplaintMutationResponseHeaders = responseHeaders(),
    ): ComplaintReceiveBudget? = ComplaintMutationReceiveBudget.checked(route, status, headers)

    private fun responseHeaders(
        media: List<String> = listOf("application/json"),
        encoding: List<String> = emptyList(),
        length: List<String> = emptyList(),
        transfer: List<String> = emptyList(),
    ): ComplaintMutationResponseHeaders = ComplaintMutationResponseHeaders(media, encoding, length, transfer)
}
