package me.manga.kira.platform.storage

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class InstallationCredentialRecordTest {
    @Test
    fun candidateHasOnlyTheInitialActiveShape() {
        val material = InstallationValueFixtures.material().valid()
        val candidate = InstallationCredentialRecord.candidate(material)
        assertEquals(1, candidate.schemaVersion)
        assertEquals(1L, candidate.credentialVersion)
        assertEquals(1L, candidate.localGeneration)
        assertEquals(InstallationCredentialState.ACTIVE, candidate.state)
        assertNull(candidate.pendingDeletionKey)
        assertSame(material, candidate.material)
        assertTrue(candidate.isInitialCandidate)
    }

    @Test
    fun schemaAndPositiveCountersAreCheckedOnRestore() {
        listOf(-1, 0, 2).forEach { schema ->
            assertIssue(InstallationValueIssue.SCHEMA, InstallationValueFixtures.record(schema = schema))
        }
        listOf(Long.MIN_VALUE, -1L, 0L).forEach { counter ->
            assertIssue(InstallationValueIssue.VERSION, InstallationValueFixtures.record(version = counter))
            assertIssue(InstallationValueIssue.GENERATION, InstallationValueFixtures.record(generation = counter))
        }
        val restored = InstallationValueFixtures.record(version = Long.MAX_VALUE, generation = Long.MAX_VALUE).valid()
        assertEquals(Long.MAX_VALUE, restored.credentialVersion)
        assertEquals(Long.MAX_VALUE, restored.localGeneration)
        assertFalse(restored.isInitialCandidate)
    }

    @Test
    fun onlyDeletionPendingMayAndMustCarryACanonicalKey() {
        InstallationCredentialState.entries.forEach { state ->
            val key = if (state == InstallationCredentialState.DELETION_PENDING) InstallationValueFixtures.KEY else null
            assertEquals(state, InstallationValueFixtures.record(state = state, key = key).valid().state)
            val wrongKey = if (key == null) InstallationValueFixtures.KEY else null
            assertIssue(
                InstallationValueIssue.STATE_KEY,
                InstallationValueFixtures.record(state = state, key = wrongKey),
            )
        }
        listOf(
            "",
            "not-a-key",
            InstallationValueFixtures.LIVE_SCOPE,
            InstallationValueFixtures.KEY.uppercase(),
        ).forEach { key ->
            assertIssue(
                InstallationValueIssue.STATE_KEY,
                InstallationValueFixtures.record(state = InstallationCredentialState.DELETION_PENDING, key = key),
            )
        }
    }

    @Test
    fun lifecycleTransitionsRetainMaterialAndVersionWhileIncrementingOnce() {
        val active = InstallationValueFixtures.record(version = 7, generation = 8).valid()
        val deletion = active.beginDeletion(InstallationValueFixtures.KEY).valid()
        val reset = active.beginLocalReset().valid()
        listOf(deletion, reset).forEach { pending ->
            assertSame(active.material, pending.material)
            assertEquals(7L, pending.credentialVersion)
            assertEquals(9L, pending.localGeneration)
            assertFalse(pending.isInitialCandidate)
        }
        assertEquals(InstallationCredentialState.DELETION_PENDING, deletion.state)
        assertEquals(InstallationValueFixtures.KEY, deletion.pendingDeletionKey)
        assertEquals(InstallationCredentialState.LOCAL_RESET_PENDING, reset.state)
        assertNull(reset.pendingDeletionKey)
        assertEquals(8L, active.localGeneration)
        assertEquals(InstallationCredentialState.ACTIVE, active.state)
    }

    @Test
    fun noPendingStateCanDowngradeOrTransitionAgain() {
        val active = InstallationValueFixtures.record().valid()
        listOf(
            active.beginLocalReset().valid(),
            active.beginDeletion(InstallationValueFixtures.KEY).valid(),
        ).forEach { pending ->
            assertIssue(InstallationValueIssue.TRANSITION, pending.beginLocalReset())
            assertIssue(InstallationValueIssue.TRANSITION, pending.beginDeletion(InstallationValueFixtures.KEY))
        }
    }

    @Test
    fun overflowAndBadKeysLeaveTheActiveRecordUnchanged() {
        val active = InstallationValueFixtures.record(generation = Long.MAX_VALUE).valid()
        assertIssue(InstallationValueIssue.GENERATION_OVERFLOW, active.beginLocalReset())
        assertIssue(InstallationValueIssue.GENERATION_OVERFLOW, active.beginDeletion(InstallationValueFixtures.KEY))
        val candidate = InstallationValueFixtures.record().valid()
        assertIssue(InstallationValueIssue.STATE_KEY, candidate.beginDeletion("bad-key"))
        assertTrue(candidate.isInitialCandidate)
        assertEquals(Long.MAX_VALUE, active.localGeneration)
    }

    @Test
    fun exactComparisonCoversMoreThanThePublicBinding() {
        val original = InstallationValueFixtures.record().valid()
        val changedSecret =
            InstallationCredentialRecord.candidate(
                InstallationValueFixtures.material(secret = "B".repeat(42) + "A").valid(),
            )
        assertTrue(original.sameBinding(changedSecret))
        assertFalse(original.sameAs(changedSecret))
        assertTrue(original.sameAs(InstallationValueFixtures.record().valid()))
        assertFalse(original.sameBinding(InstallationValueFixtures.record(version = 2).valid()))
        assertFalse(original.sameBinding(InstallationValueFixtures.record(generation = 2).valid()))
    }

    @Test
    fun recordAndReadWrapperDiagnosticsAreRedacted() {
        val record =
            InstallationValueFixtures
                .record()
                .valid()
                .beginDeletion(InstallationValueFixtures.KEY)
                .valid()
        listOf(record.toString(), CredentialReadResult.Present(record).toString()).forEach { diagnostic ->
            assertFalse(diagnostic.contains(record.material.installationId))
            assertFalse(diagnostic.contains(record.material.secret))
            assertFalse(diagnostic.contains(InstallationValueFixtures.KEY))
        }
    }

    private fun assertIssue(
        expected: InstallationValueIssue,
        result: InstallationValueResult<*>,
    ) {
        assertEquals(expected, assertIs<InstallationValueResult.Invalid>(result).issue)
    }
}
