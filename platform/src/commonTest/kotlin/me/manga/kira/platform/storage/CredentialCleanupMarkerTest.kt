package me.manga.kira.platform.storage

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CredentialCleanupMarkerTest {
    @Test
    fun nullGenerationIsReservedForUnreadableRecovery() {
        CredentialCleanupReason.entries.forEach { reason ->
            val unreadable = reason == CredentialCleanupReason.UNREADABLE_RESET_CONFIRMED
            val marker = CredentialCleanupMarker.checked(1, if (unreadable) null else 4, reason).valid()
            assertEquals(reason, marker.reason)
            if (unreadable) assertNull(marker.expectedGeneration) else assertEquals(4L, marker.expectedGeneration)
            val rejected = CredentialCleanupMarker.checked(1, if (unreadable) 4 else null, reason)
            assertEquals(
                InstallationValueIssue.MARKER_REASON,
                assertIs<InstallationValueResult.Invalid>(rejected).issue,
            )
        }
    }

    @Test
    fun unsupportedSchemaAndNonpositiveGenerationsAreRejected() {
        val reason = CredentialCleanupReason.USER_RESET_CONFIRMED
        listOf(-1L, 0L, Long.MIN_VALUE).forEach { generation ->
            val result = CredentialCleanupMarker.checked(1, generation, reason)
            assertEquals(InstallationValueIssue.GENERATION, assertIs<InstallationValueResult.Invalid>(result).issue)
        }
        val result = CredentialCleanupMarker.checked(2, 1, reason)
        assertEquals(InstallationValueIssue.SCHEMA, assertIs<InstallationValueResult.Invalid>(result).issue)
    }

    @Test
    fun exactMarkerComparisonIncludesReasonAndGeneration() {
        val first = CredentialCleanupMarker.checked(1, 7, CredentialCleanupReason.USER_RESET_CONFIRMED).valid()
        val equal = CredentialCleanupMarker.checked(1, 7, CredentialCleanupReason.USER_RESET_CONFIRMED).valid()
        val otherGeneration =
            CredentialCleanupMarker
                .checked(1, 8, CredentialCleanupReason.USER_RESET_CONFIRMED)
                .valid()
        val otherReason =
            CredentialCleanupMarker
                .checked(1, 7, CredentialCleanupReason.SERVER_TERMINAL_CONFIRMED)
                .valid()
        assertTrue(first.sameAs(equal))
        assertFalse(first.sameAs(otherGeneration))
        assertFalse(first.sameAs(otherReason))
        assertEquals("CredentialCleanupMarker(redacted)", first.toString())
        assertEquals("CleanupMarkerReadResult.Present(redacted)", CleanupMarkerReadResult.Present(first).toString())
        // This is a declared ceiling, not evidence about any native encoder/allocation.
        assertEquals(512, CredentialCleanupMarker.MAX_ENCODED_BYTES)
    }
}
