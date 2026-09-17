package me.manga.kira.data.remote.complaint

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import me.manga.kira.core.complaint.ComplaintMutationTransportPolicy as Policy

class ComplaintMutationReceiveBudgetTest {
    @Test
    fun onlyCreate201JsonGetsTheLargerBudgetAndProblemsNeverDo() {
        val create = assertNotNull(budget(ComplaintMutationRoute.CREATE, CREATED))
        assertEquals(Policy.MAX_CREATE_ACKNOWLEDGEMENT_BYTES, create.remainingBytes)
        assertTrue(create.accept(Policy.MAX_CREATE_ACKNOWLEDGEMENT_BYTES.toULong()))
        assertFalse(create.accept(1uL))
        listOf(200L, 201L, 204L, 301L, 401L, 409L, 500L, 503L).forEach { status ->
            val response = assertNotNull(budget(ComplaintMutationRoute.STATUS, status))
            assertIs<ComplaintSessionReceiveBudget>(response)
            assertEquals(Policy.MAX_STATUS_OR_PROBLEM_BYTES, response.remainingBytes)
        }
        listOf(200L, 401L, 409L, 500L).forEach { status ->
            assertIs<ComplaintSessionReceiveBudget>(assertNotNull(budget(ComplaintMutationRoute.CREATE, status)))
        }
        listOf(emptyList(), listOf("application/problem+json"), listOf("application/json", "application/json"))
            .forEach { media ->
                assertIs<ComplaintSessionReceiveBudget>(
                    assertNotNull(budget(ComplaintMutationRoute.CREATE, CREATED, media)),
                )
            }
    }

    @Test
    fun declaredBoundaryIsRouteSpecificAndTheLargerBudgetStillChecksExactCompletion() {
        val cap = Policy.MAX_CREATE_ACKNOWLEDGEMENT_BYTES
        val create = assertNotNull(budget(ComplaintMutationRoute.CREATE, CREATED, length = listOf("$cap")))
        assertTrue(create.accept((cap - 1).toULong()))
        assertFalse(create.isComplete())
        assertTrue(create.accept(1uL))
        assertTrue(create.isComplete())
        assertFalse(create.accept(ULong.MAX_VALUE))
        assertEquals(cap, create.receivedBytes)
        assertNull(budget(ComplaintMutationRoute.CREATE, CREATED, length = listOf("${cap + 1}")))
        assertNull(budget(ComplaintMutationRoute.STATUS, CREATED, length = listOf("$cap")))
        assertNull(budget(ComplaintMutationRoute.CREATE, CONFLICT, length = listOf("$cap")))
    }

    @Test
    fun largerBudgetPreservesIdentityAndUnambiguousFraming() {
        assertNull(budget(ComplaintMutationRoute.CREATE, CREATED, encoding = listOf("gzip")))
        assertNull(budget(ComplaintMutationRoute.CREATE, CREATED, length = listOf("1", "1")))
        assertNull(budget(ComplaintMutationRoute.CREATE, CREATED, length = listOf("1, 1")))
        assertNull(
            budget(ComplaintMutationRoute.CREATE, CREATED, length = listOf("1"), transfer = listOf("chunked")),
        )
        val chunked = assertNotNull(budget(ComplaintMutationRoute.CREATE, CREATED, transfer = listOf("chunked")))
        assertTrue(chunked.accept(Policy.MAX_CREATE_ACKNOWLEDGEMENT_BYTES.toULong()))
        assertFalse(chunked.accept(1uL))
    }

    private fun budget(
        route: ComplaintMutationRoute,
        status: Long,
        media: List<String> = listOf("application/json"),
        encoding: List<String> = emptyList(),
        length: List<String> = emptyList(),
        transfer: List<String> = emptyList(),
    ): ComplaintReceiveBudget? =
        ComplaintMutationReceiveBudget.checked(route, status, media, encoding, length, transfer)

    private companion object {
        const val CREATED = 201L
        const val CONFLICT = 409L
    }
}
