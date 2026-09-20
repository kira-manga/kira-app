package me.manga.kira.data.remote.complaint

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import me.manga.kira.core.complaint.ComplaintMutationTransportPolicy as Policy

@Suppress("MagicNumber") // Direct204 has a zero-byte cap, separate from existing JSON cells.
class ComplaintMutationOwnerDeleteReceiveBudgetTest {
    @Test
    fun direct204HasAnExplicitZeroBudgetAndRejectedBytesNeverAdvanceAccounting() {
        assertEquals(0, Policy.MAX_OWNER_DELETE_ACKNOWLEDGEMENT_BYTES)
        listOf(emptyList(), listOf("identity"), listOf("IDENTITY")).forEach { encoding ->
            val budget = assertNotNull(budget(headers = responseHeaders(encoding = encoding)))
            assertIs<ComplaintMutationReceiveBudget>(budget)
            assertEquals(0, budget.remainingBytes)
            assertTrue(budget.accept(0uL))
            assertTrue(budget.isComplete()) // Native owners still require actual clean completion/EOF.
            assertFalse(budget.accept(1uL))
            assertFalse(budget.accept(ULong.MAX_VALUE))
            assertEquals(0, budget.receivedBytes)
            assertEquals(0, budget.remainingBytes)
        }
    }

    @Test
    fun anyDirect204LengthMediaOrTransferHeaderRejectsWithoutAJsonBudgetFallback() {
        listOf(listOf(""), listOf("0"), listOf("00"), listOf("0, 0"), listOf("0", "0"), listOf("1"))
            .forEach { length -> assertNull(budget(headers = responseHeaders(length = length))) }
        listOf(listOf(""), listOf("application/json"), listOf("application/problem+json"), listOf("text/plain"))
            .forEach { media -> assertNull(budget(headers = responseHeaders(media = media))) }
        listOf(listOf(""), listOf("chunked"), listOf("identity"), listOf("chunked", "chunked"))
            .forEach { transfer -> assertNull(budget(headers = responseHeaders(transfer = transfer))) }
    }

    @Test
    fun direct204EncodingMustBeAbsentOrExactlyOneBoundedIdentityValue() {
        listOf(
            listOf(""),
            listOf("gzip"),
            listOf(" identity"),
            listOf("identity "),
            listOf("identity, identity"),
            listOf("identity", "identity"),
            listOf("x".repeat(129)),
        ).forEach { encoding -> assertNull(budget(headers = responseHeaders(encoding = encoding))) }
    }

    @Test
    fun deleteOtherStatusesAndOldRoutesRetainTheirExistingSixteenOrThirtyTwoKiBBudgets() {
        listOf(200L, 201L, 202L, 400L, 401L, 404L, 409L, 412L, 421L, 503L).forEach { status ->
            val budget =
                assertNotNull(budget(status = status, headers = responseHeaders(media = listOf("application/json"))))
            assertIs<ComplaintSessionReceiveBudget>(budget)
            assertEquals(Policy.MAX_STATUS_OR_PROBLEM_BYTES, budget.remainingBytes)
        }
        listOf(
            ComplaintMutationRoute.CREATE to 201L,
            ComplaintMutationRoute.REPLY to 201L,
            ComplaintMutationRoute.EDIT to 200L,
        ).forEach { (route, status) ->
            val budget = assertNotNull(budget(route, status, responseHeaders(media = listOf("application/json"))))
            assertEquals(32 * 1_024, budget.remainingBytes)
        }
        ComplaintMutationRoute.entries.filterNot { it == ComplaintMutationRoute.OWNER_DELETE }.forEach { route ->
            val budget = assertNotNull(budget(route, 204))
            assertIs<ComplaintSessionReceiveBudget>(budget)
            assertEquals(Policy.MAX_STATUS_OR_PROBLEM_BYTES, budget.remainingBytes)
        }
        val status =
            assertNotNull(budget(ComplaintMutationRoute.STATUS, 200, responseHeaders(media = listOf("application/json"))))
        assertEquals(Policy.MAX_STATUS_OR_PROBLEM_BYTES, status.remainingBytes)
    }

    private fun budget(
        route: ComplaintMutationRoute = ComplaintMutationRoute.OWNER_DELETE,
        status: Long = 204,
        headers: ComplaintMutationResponseHeaders = responseHeaders(),
    ): ComplaintReceiveBudget? = ComplaintMutationReceiveBudget.checked(route, status, headers)

    private fun responseHeaders(
        media: List<String> = emptyList(),
        encoding: List<String> = emptyList(),
        length: List<String> = emptyList(),
        transfer: List<String> = emptyList(),
    ): ComplaintMutationResponseHeaders = ComplaintMutationResponseHeaders(media, encoding, length, transfer)
}
