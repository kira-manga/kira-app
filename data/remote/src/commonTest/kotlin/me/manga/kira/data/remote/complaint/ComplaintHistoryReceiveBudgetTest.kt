package me.manga.kira.data.remote.complaint

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ComplaintHistoryReceiveBudgetTest {
    @Test
    fun only200UsesTwoMibibytesAndCannotChangeTheInstallationDefault() {
        val success = assertNotNull(budget(SUCCESS_STATUS))
        assertIs<ComplaintHistoryReceiveBudget>(success)
        assertEquals(ComplaintHistoryReceiveBudget.MAX_BYTES, success.remainingBytes)
        assertTrue(success.accept(ComplaintHistoryReceiveBudget.MAX_BYTES.toULong()))
        assertFalse(success.accept(1uL))
        assertEquals(ComplaintHistoryReceiveBudget.MAX_BYTES, success.receivedBytes)
        val installation = assertNotNull(ComplaintSessionReceiveBudget.checked(emptyList(), emptyList(), emptyList()))
        assertEquals(ComplaintSessionReceiveBudget.MAX_BYTES, installation.remainingBytes)
        assertFalse(installation.accept((ComplaintSessionReceiveBudget.MAX_BYTES + 1).toULong()))
    }

    @Test
    fun everyOtherStatusUsesTheIndependentSixteenKibibyteBudget() {
        listOf(201L, 202L, 204L, 206L, 301L, 401L, 403L, 404L, 408L, 421L, 429L, 500L, 503L).forEach { status ->
            val problem = assertNotNull(budget(status))
            assertIs<ComplaintSessionReceiveBudget>(problem)
            assertEquals(ComplaintSessionReceiveBudget.MAX_BYTES, problem.remainingBytes)
            assertTrue(problem.accept(ComplaintSessionReceiveBudget.MAX_BYTES.toULong()))
            assertFalse(problem.accept(1uL))
            assertNull(budget(status, length = listOf((ComplaintSessionReceiveBudget.MAX_BYTES + 1).toString())))
        }
    }

    @Test
    fun exactDeclaredListLengthIsCheckedAtCompletionAndOneOverIsRefusedEarly() {
        val exact =
            assertNotNull(budget(SUCCESS_STATUS, length = listOf(ComplaintHistoryReceiveBudget.MAX_BYTES.toString())))
        assertFalse(exact.isComplete())
        assertTrue(exact.accept((ComplaintHistoryReceiveBudget.MAX_BYTES - 1).toULong()))
        assertFalse(exact.isComplete())
        assertTrue(exact.accept(1uL))
        assertTrue(exact.isComplete())
        assertNull(budget(SUCCESS_STATUS, length = listOf((ComplaintHistoryReceiveBudget.MAX_BYTES + 1).toString())))
        val unknown = assertNotNull(budget(SUCCESS_STATUS, transfer = listOf("chunked")))
        assertTrue(unknown.isComplete())
    }

    @Test
    fun largeBudgetKeepsHeaderGrammarAndRejectsUnsignedOverrunWithoutChangingTheCount() {
        listOf(listOf("gzip"), listOf("identity", "identity"), listOf("identity, identity"))
            .forEach { assertNull(budget(SUCCESS_STATUS, encoding = it)) }
        listOf("", "+1", " 1", "1, 1", "-1", "2147483648", "0".repeat(HEADER_OVERFLOW_CHARACTERS))
            .forEach { assertNull(budget(SUCCESS_STATUS, length = listOf(it))) }
        assertNull(budget(SUCCESS_STATUS, length = listOf("1", "1")))
        assertNull(budget(SUCCESS_STATUS, length = listOf("1"), transfer = listOf("chunked")))
        assertNull(budget(SUCCESS_STATUS, transfer = listOf("gzip")))
        val checked = assertNotNull(budget(SUCCESS_STATUS))
        assertTrue(checked.accept(1uL))
        assertFalse(checked.accept(ULong.MAX_VALUE))
        assertEquals(1, checked.receivedBytes)
    }

    private fun budget(
        status: Long,
        encoding: List<String> = emptyList(),
        length: List<String> = emptyList(),
        transfer: List<String> = emptyList(),
    ): ComplaintReceiveBudget? = ComplaintHistoryReceiveBudget.checked(status, encoding, length, transfer)

    private companion object {
        const val SUCCESS_STATUS = 200L
        const val HEADER_OVERFLOW_CHARACTERS = 129
    }
}
