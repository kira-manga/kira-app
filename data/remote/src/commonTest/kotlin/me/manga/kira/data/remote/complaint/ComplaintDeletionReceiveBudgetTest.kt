package me.manga.kira.data.remote.complaint

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import me.manga.kira.core.complaint.ComplaintDeletionTransportPolicy as Policy

class ComplaintDeletionReceiveBudgetTest {
    @Test
    fun successfulBodiesHaveZeroBudgetEvenWhenNoLengthWasDeclared() {
        listOf(202L, 204L).forEach { status ->
            listOf(emptyList(), listOf("0")).forEach { length ->
                val response = assertNotNull(budget(status, responseHeaders(media = emptyList(), length = length)))
                assertEquals(0, response.remainingBytes)
                assertTrue(response.accept(0uL))
                assertTrue(response.isComplete())
                assertFalse(response.accept(1uL))
                assertFalse(response.accept(ULong.MAX_VALUE))
                assertEquals(0, response.receivedBytes)
            }
            assertNotNull(budget(status, responseHeaders(media = listOf("application/json"))))
            assertNull(budget(status, responseHeaders(length = listOf("1"))))
            assertNull(budget(status, responseHeaders()))
        }
    }

    @Test
    fun problemsHaveExactSixteenKibBoundaryAndDeclaredLengthIsCheckedAtCompletion() {
        val cap = Policy.MAX_PROBLEM_BYTES
        val response = assertNotNull(budget(410, responseHeaders(length = listOf("$cap"))))
        assertTrue(response.accept((cap - 1).toULong()))
        assertFalse(response.isComplete())
        assertTrue(response.accept(1uL))
        assertTrue(response.isComplete())
        assertFalse(response.accept(1uL))
        assertEquals(cap, response.receivedBytes)
        assertNull(budget(410, responseHeaders(length = listOf("${cap + 1}"))))
        listOf(200L, 201L, 301L, 307L, 399L, 600L).forEach { assertNull(budget(it)) }
    }

    @Test
    fun mediaCodingDuplicateAndConflictingFramingNeverCreateABudget() {
        listOf(
            responseHeaders(media = emptyList()),
            responseHeaders(media = listOf("application/json")),
            responseHeaders(media = listOf("application/problem+json", "application/problem+json")),
            responseHeaders(encoding = listOf("gzip")),
            responseHeaders(encoding = listOf("identity", "identity")),
            responseHeaders(length = listOf("1", "1")),
            responseHeaders(length = listOf("1,1")),
            responseHeaders(length = listOf("-1")),
            responseHeaders(length = listOf("1"), transfer = listOf("chunked")),
            responseHeaders(transfer = listOf("gzip")),
        ).forEach { assertNull(budget(503, it)) }
        val chunked = assertNotNull(budget(503, responseHeaders(transfer = listOf("chunked"))))
        assertTrue(chunked.accept(Policy.MAX_PROBLEM_BYTES.toULong()))
        assertFalse(chunked.accept(1uL))
    }

    private fun budget(
        status: Long,
        headers: ComplaintDeletionResponseHeaders = responseHeaders(),
    ): ComplaintReceiveBudget? = ComplaintDeletionReceiveBudget.checked(status, headers)

    private fun responseHeaders(
        media: List<String> = listOf("application/problem+json"),
        encoding: List<String> = emptyList(),
        length: List<String> = emptyList(),
        transfer: List<String> = emptyList(),
    ): ComplaintDeletionResponseHeaders = ComplaintDeletionResponseHeaders(media, encoding, length, transfer)
}
