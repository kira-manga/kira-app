package me.manga.kira.platform.storage

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotSame
import kotlin.test.assertTrue

class PendingComplaintSnapshotTest {
    @Test
    fun slotOwnsCopiesOfBothInputAndReturnedBytes() {
        val input = byteArrayOf(1, 2, 3)
        val slot = InstallationValueFixtures.slot(1, input)
        input.fill(9)
        val output = slot.bytes()
        output.fill(8)
        assertContentEquals(byteArrayOf(1, 2, 3), slot.bytes())
        assertNotSame(slot.bytes(), slot.bytes())
        assertTrue(slot.sameAs(InstallationValueFixtures.slot(1, byteArrayOf(1, 2, 3))))
        assertFalse(slot.sameAs(InstallationValueFixtures.slot(2, byteArrayOf(1, 2, 3))))
        assertFalse(slot.sameAs(InstallationValueFixtures.slot(1, byteArrayOf(1, 2, 4))))
    }

    @Test
    fun slotEnforcesCanonicalIdentityAndTwoKiBBoundary() {
        val maximum = InstallationValueFixtures.slot(1, ByteArray(2 * 1024))
        assertEquals(2 * 1024, maximum.size)
        val oversized = PendingComplaintSlot.checked(InstallationValueFixtures.ID, ByteArray(2 * 1024 + 1))
        assertEquals(InstallationValueIssue.SLOT_SIZE, assertIs<InstallationValueResult.Invalid>(oversized).issue)
        listOf("", InstallationValueFixtures.LIVE_SCOPE, InstallationValueFixtures.KEY.uppercase()).forEach { id ->
            val invalid = PendingComplaintSlot.checked(id, byteArrayOf())
            assertEquals(InstallationValueIssue.SLOT_ID, assertIs<InstallationValueResult.Invalid>(invalid).issue)
        }
    }

    @Test
    fun opaqueEmptyAndProseBytesMakeNoSemanticValidationClaim() {
        val emptyBytes = InstallationValueFixtures.slot(1, byteArrayOf())
        val prose = InstallationValueFixtures.slot(2, "opaque fixture, not a request codec".encodeToByteArray())
        val snapshot = PendingComplaintSnapshot.checked(listOf(emptyBytes, prose)).valid()
        assertEquals(2, snapshot.size)
        assertFalse(snapshot.isEmpty)
        assertFalse(prose.toString().contains("opaque fixture"))
        assertFalse(snapshot.toString().contains(prose.id))
        assertEquals("PendingReadResult.Verified(redacted)", PendingReadResult.Verified(snapshot).toString())
    }

    @Test
    fun snapshotOwnsItsInputAndReturnedLists() {
        val caller = mutableListOf(InstallationValueFixtures.slot(1), InstallationValueFixtures.slot(2))
        val snapshot = PendingComplaintSnapshot.checked(caller).valid()
        caller.clear()
        val returned = snapshot.entries() as MutableList<PendingComplaintSlot>
        returned.clear()
        assertEquals(2, snapshot.size)
        assertEquals(2, snapshot.entries().size)
        assertNotSame(snapshot.entries(), snapshot.entries())
    }

    @Test
    fun duplicateIdentityIsRejectedEvenWhenItsBytesDiffer() {
        val first = InstallationValueFixtures.slot(1, byteArrayOf(1))
        val differentBytes = InstallationValueFixtures.slot(1, byteArrayOf(2))
        val result = PendingComplaintSnapshot.checked(listOf(first, differentBytes))
        assertEquals(InstallationValueIssue.DUPLICATE_SLOT, assertIs<InstallationValueResult.Invalid>(result).issue)
    }

    @Test
    fun sixteenFullSlotsReachExactlyThirtyTwoKiBWithoutEviction() {
        val slots = (1..16).map { InstallationValueFixtures.slot(it, ByteArray(2 * 1024)) }
        val maximum = PendingComplaintSnapshot.checked(slots).valid()
        assertEquals(16, maximum.size)
        assertEquals(32 * 1024, maximum.logicalBytes)
        val tooMany = PendingComplaintSnapshot.checked(slots + InstallationValueFixtures.slot(17))
        assertEquals(InstallationValueIssue.SLOT_COUNT, assertIs<InstallationValueResult.Invalid>(tooMany).issue)
        assertEquals(16, maximum.entries().size)
        // With checked 2 KiB slots, exceeding 32 KiB also necessarily violates the count limit.
        // The Android 64 KiB physical ceiling still requires a native encoded-storage proof.
        assertEquals(64 * 1024, PendingComplaintSnapshot.MAX_ANDROID_PHYSICAL_BYTES)
    }

    @Test
    fun emptySnapshotIsVerifiedInventoryRatherThanMissingStorage() {
        val snapshot = PendingComplaintSnapshot.checked(emptyList()).valid()
        assertTrue(snapshot.isEmpty)
        assertEquals(0, snapshot.size)
        assertEquals(0, snapshot.logicalBytes)
    }
}
