package me.manga.kira.platform.storage

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import me.manga.kira.platform.storage.AndroidInstallationCredentialStoreTest.MemoryFileIo
import me.manga.kira.platform.storage.AndroidInstallationCredentialStoreTest.PrimitiveTrace
import org.junit.Test
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Grouped pending-frame and adapter faults reuse the checked-file primitive double, not a new harness.
 * Literal sizes/offsets/generations are deliberate boundary vectors; the cohesive matrix stays together.
 */
@Suppress("TooManyFunctions", "MagicNumber")
class AndroidPendingComplaintActionStoreTest {
    @Test
    fun emptyAndOpaqueSlotsRoundTripThroughThePhysicalFrame() =
        runTest {
            val fixture = Fixture()
            assertTrue(fixture.inventory().isEmpty)
            val first = slot(1, byteArrayOf())
            val second = slot(2, byteArrayOf(0, -1, 0, 127))
            assertEquals(PendingCreateResult.Stored, fixture.store.createIfMissing(second))
            assertEquals(PendingCreateResult.Stored, fixture.store.createIfMissing(first))
            val entries = fixture.inventory().entries()
            assertEquals(listOf(first.id, second.id), entries.map { it.id })
            assertTrue(entries[0].sameAs(first))
            assertTrue(entries[1].sameAs(second))
            assertContentEquals(frame(listOf(first, second)), fixture.io.get(SLOT))
            entries[1].bytes().fill(0)
            assertTrue(fixture.inventory().entries()[1].sameAs(second))
            assertTrue(
                fixture.io.readLimits.all { it == (SLOT to PendingComplaintSnapshot.MAX_ANDROID_PHYSICAL_BYTES) },
            )
        }

    @Test
    fun sixteenFullSlotsAreRetainedAndSeventeenthNeverEvicts() =
        runTest {
            val fixture = Fixture()
            val slots =
                (1..16).map { number -> slot(number, ByteArray(PendingComplaintSlot.MAX_BYTES) { it.toByte() }) }
            for (slot in slots.reversed()) {
                assertEquals(PendingCreateResult.Stored, fixture.store.createIfMissing(slot))
            }
            val snapshot = fixture.inventory()
            assertEquals(PendingComplaintSnapshot.MAX_SLOTS, snapshot.size)
            assertEquals(PendingComplaintSnapshot.MAX_LOGICAL_BYTES, snapshot.logicalBytes)
            val before = assertNotNull(fixture.io.get(SLOT))
            assertEquals(33386, before.size)
            fixture.trace.events.clear()
            permanent(InstallationPermanentFailure.TOO_LARGE, fixture.store.createIfMissing(slot(17)))
            assertEquals(PendingCreateResult.AlreadyPresent, fixture.store.createIfMissing(slot(1, byteArrayOf(9))))
            assertFalse(fixture.trace.events.contains("start.$SLOT"))
            assertContentEquals(before, fixture.io.get(SLOT))
            assertEquals(slots.map { it.id }, fixture.inventory().entries().map { it.id })
        }

    @Test
    fun malformedAndOneOverFramesAreNotDecodedAsPartialInventory() =
        runTest {
            val valid = frame(listOf(slot(1, byteArrayOf(1, 2, 3))))
            val tooLarge = InstallationPermanentFailure.TOO_LARGE
            val corrupt = InstallationPermanentFailure.CORRUPT
            val badFrames =
                listOf(
                    byteArrayOf() to corrupt,
                    valid.copyOf(4) to corrupt,
                    frame(schema = 2).copyOf(5) to InstallationPermanentFailure.UNSUPPORTED,
                    frame(declaredCount = 17) to tooLarge,
                    frame(declaredLogical = PendingComplaintSnapshot.MAX_LOGICAL_BYTES + 1) to tooLarge,
                    frame(declaredLogical = -1) to tooLarge,
                    valid.copyOf().also { ByteBuffer.wrap(it).putShort(HEADER_BYTES + UUID_BYTES, 2049) } to tooLarge,
                    valid.copyOf(valid.size - 1) to corrupt,
                    valid.copyOf().also { it[HEADER_BYTES] = 'A'.code.toByte() } to corrupt,
                    frame(listOf(slot(2), slot(1))) to corrupt,
                    frame(listOf(slot(1), slot(1, byteArrayOf(9)))) to corrupt,
                    frame(listOf(slot(1)), declaredCount = 0) to corrupt,
                    frame(listOf(slot(1)), declaredLogical = 0) to corrupt,
                    (valid + byteArrayOf(0)) to corrupt,
                    ByteArray(PendingComplaintSnapshot.MAX_ANDROID_PHYSICAL_BYTES).also {
                        frame().copyInto(it)
                    } to corrupt,
                    ByteArray(PendingComplaintSnapshot.MAX_ANDROID_PHYSICAL_BYTES + 1) to tooLarge,
                )
            for ((bytes, reason) in badFrames) {
                val fixture = Fixture()
                fixture.io.put(SLOT, bytes)
                permanent(reason, fixture.store.read())
                assertContentEquals(bytes, fixture.io.get(SLOT))
                assertFalse(fixture.trace.events.any { it.startsWith("start.") || it.startsWith("remove.") })
                assertTrue(
                    fixture.io.readLimits.all { it.second == PendingComplaintSnapshot.MAX_ANDROID_PHYSICAL_BYTES },
                )
            }
        }

    @Test
    fun replacementAndDeletionRequireExactOldBytesAndPreserveOtherSlots() =
        runTest {
            val fixture = Fixture()
            val first = slot(1)
            val second = slot(2)
            val updated = slot(1, byteArrayOf(7))
            fixture.seed(first, second)
            val before = fixture.io.get(SLOT)
            assertEquals(PendingReplaceResult.Stale, fixture.store.replace(updated, first))
            assertEquals(PendingReplaceResult.Stale, fixture.store.replace(first, slot(3)))
            assertEquals(PendingReplaceResult.Missing, fixture.store.replace(slot(9), slot(9, byteArrayOf(4))))
            assertEquals(PendingDeleteResult.Stale, fixture.store.delete(updated))
            assertEquals(PendingDeleteResult.Missing, fixture.store.delete(slot(9)))
            assertContentEquals(before, fixture.io.get(SLOT))
            assertEquals(PendingReplaceResult.Stored, fixture.store.replace(first, updated))
            assertContentEquals(frame(listOf(updated, second)), fixture.io.get(SLOT))
            assertEquals(PendingDeleteResult.Stale, fixture.store.delete(first))
            assertEquals(PendingDeleteResult.Deleted, fixture.store.delete(updated))
            assertTrue(
                fixture
                    .inventory()
                    .entries()
                    .single()
                    .sameAs(second),
            )
            assertEquals(PendingDeleteResult.Deleted, fixture.store.delete(second))
            assertContentEquals(frame(), fixture.io.get(SLOT))
            assertTrue(fixture.inventory().isEmpty)
        }

    @Test
    fun createAndMissingRepliesStillRequireCompleteValidInventory() =
        runTest {
            val fixture = Fixture()
            val bad = frame(listOf(slot(1), slot(2))) + byteArrayOf(0)
            fixture.io.put(SLOT, bad)
            permanent(InstallationPermanentFailure.CORRUPT, fixture.store.createIfMissing(slot(1)))
            permanent(InstallationPermanentFailure.CORRUPT, fixture.store.replace(slot(9), slot(9)))
            permanent(InstallationPermanentFailure.CORRUPT, fixture.store.delete(slot(9)))
            assertContentEquals(bad, fixture.io.get(SLOT))
            assertFalse(fixture.trace.events.any { it.startsWith("start.") || it.startsWith("remove.") })
            val unavailable = Fixture()
            unavailable.trace.failures["exists.$SLOT.BASE"] = IOException()
            temporary(InstallationTemporaryFailure.IO_FAILURE, unavailable.store.read())
        }

    @Test
    fun silentFinishAndWholeFrameReadBackMismatchNeverReportStored() =
        runTest {
            for (fault in listOf("rename", "close", "other-slot")) {
                val fixture = Fixture()
                val old = slot(1)
                val updated = slot(1, byteArrayOf(7))
                fixture.seed(old, slot(2))
                when (fault) {
                    "rename" -> fixture.io.silentFinish = true
                    "close" -> fixture.io.finishKeepsOpen = true
                    else ->
                        fixture.io.afterFinish = {
                            fixture.io.put(SLOT, frame(listOf(updated, slot(2, byteArrayOf(9)))))
                        }
                }
                permanent(InstallationPermanentFailure.READ_BACK_MISMATCH, fixture.store.replace(old, updated))
                assertEquals(0, fixture.io.rollbacks)
                assertEquals(0, fixture.io.openWrites)
                assertNotNull(fixture.io.get(SLOT))
            }
        }

    @Test
    fun syncRollbackAndPostFinishFailuresRetainEvidence() =
        runTest {
            for (fault in listOf("sync", "rollback", "directory")) {
                val fixture = Fixture()
                fixture.seed(slot(1))
                val before = fixture.io.get(SLOT)
                if (fault == "directory") {
                    fixture.io.afterFinish = { fixture.trace.failures["syncDirectory"] = IOException() }
                } else {
                    fixture.trace.failures["write.sync"] = IOException()
                    fixture.io.silentRollback = fault == "rollback"
                }
                val reason =
                    if (fault == "rollback") {
                        InstallationTemporaryFailure.UNCERTAIN
                    } else {
                        InstallationTemporaryFailure.IO_FAILURE
                    }
                temporary(reason, fixture.store.replace(slot(1), slot(1, byteArrayOf(7))))
                assertEquals(if (fault == "directory") 0 else 1, fixture.io.rollbacks)
                assertEquals(0, fixture.io.openWrites)
                if (fault != "directory") assertContentEquals(before, fixture.io.get(SLOT))
                if (fault == "rollback") assertNotNull(fixture.io.get(SLOT, NEW))
            }
        }

    @Test
    fun interruptedVariantsNeedAValidBaseAndKnownSchema() =
        runTest {
            val resumed = Fixture()
            resumed.seed(slot(1))
            resumed.io.put(SLOT, byteArrayOf(0), NEW)
            assertTrue(
                resumed
                    .inventory()
                    .entries()
                    .single()
                    .sameAs(slot(1)),
            )
            assertNull(resumed.io.get(SLOT, NEW))
            for (pending in listOf(frame(schema = 2).copyOf(5), ByteArray(65537))) {
                val fixture = Fixture()
                fixture.seed(slot(1))
                fixture.io.put(SLOT, pending, NEW)
                val reason =
                    if (pending.size > PendingComplaintSnapshot.MAX_ANDROID_PHYSICAL_BYTES) {
                        InstallationPermanentFailure.TOO_LARGE
                    } else {
                        InstallationPermanentFailure.UNSUPPORTED
                    }
                permanent(reason, fixture.store.read())
                assertContentEquals(pending, fixture.io.get(SLOT, NEW))
                assertFalse(fixture.trace.events.any { it.startsWith("remove.") })
            }
            for (variant in listOf(NEW, BACKUP)) {
                val fixture = Fixture()
                fixture.io.put(SLOT, frame(listOf(slot(1))), variant)
                permanent(InstallationPermanentFailure.CORRUPT, fixture.store.read())
                assertNull(fixture.io.get(SLOT))
                assertNotNull(fixture.io.get(SLOT, variant))
            }
            val damaged = Fixture()
            damaged.io.put(SLOT, byteArrayOf(0))
            damaged.io.put(SLOT, frame(listOf(slot(1))), NEW)
            permanent(InstallationPermanentFailure.CORRUPT, damaged.store.read())
            assertContentEquals(byteArrayOf(0), damaged.io.get(SLOT))
            assertNotNull(damaged.io.get(SLOT, NEW))
        }

    @Test
    fun confirmedRecoveryPreflightsEveryVariantBeforeDeletingAnything() =
        runTest {
            val future = Fixture()
            future.io.put(SLOT, byteArrayOf(0))
            future.io.put(SLOT, ByteArray(PendingComplaintSnapshot.MAX_ANDROID_PHYSICAL_BYTES + 1), NEW)
            future.io.put(SLOT, frame(schema = 2).copyOf(5), BACKUP)
            permanent(InstallationPermanentFailure.UNSUPPORTED, future.store.clearForConfirmedRecovery())
            assertFalse(future.trace.events.any { it.startsWith("remove.") })
            assertNotNull(future.io.get(SLOT))
            assertNotNull(future.io.get(SLOT, NEW))
            assertNotNull(future.io.get(SLOT, BACKUP))
            val failures =
                listOf(
                    InstallationStorageFailure.TemporarilyUnavailable(InstallationTemporaryFailure.LOCKED),
                    InstallationStorageFailure.TemporarilyUnavailable(InstallationTemporaryFailure.IO_FAILURE),
                    InstallationStorageFailure.TemporarilyUnavailable(InstallationTemporaryFailure.UNCERTAIN),
                    InstallationStorageFailure.PermanentFailure(InstallationPermanentFailure.UNSUPPORTED),
                    InstallationStorageFailure.PermanentFailure(InstallationPermanentFailure.STATE_CHANGED),
                    InstallationStorageFailure.PermanentFailure(InstallationPermanentFailure.READ_BACK_MISMATCH),
                )
            for (failure in failures) {
                val fixture = Fixture()
                fixture.io.put(SLOT, byteArrayOf(0))
                fixture.io.put(SLOT, frame(listOf(slot(1))), NEW)
                fixture.trace.failures["read.$SLOT.NEW"] = AndroidCredentialProblem(failure)
                assertEquals(failure, fixture.store.clearForConfirmedRecovery())
                assertFalse(fixture.trace.events.any { it.startsWith("remove.") })
                assertNotNull(fixture.io.get(SLOT))
                assertNotNull(fixture.io.get(SLOT, NEW))
            }
        }

    @Test
    fun confirmedRecoveryVerifiesOnlyTheFixedPendingServiceAndRetries() =
        runTest {
            for (fault in listOf("silent-new", "base-io")) {
                val fixture = Fixture()
                val credential = byteArrayOf(11)
                val marker = byteArrayOf(12)
                fixture.io.put(AndroidCredentialSlot.CREDENTIAL, credential)
                fixture.io.put(AndroidCredentialSlot.MARKER, marker)
                fixture.io.put(SLOT, ByteArray(PendingComplaintSnapshot.MAX_ANDROID_PHYSICAL_BYTES + 1))
                fixture.io.put(SLOT, byteArrayOf(0), NEW)
                fixture.io.put(SLOT, byteArrayOf(0), BACKUP)
                if (fault == "silent-new") {
                    fixture.io.silentDelete = SLOT to NEW
                    permanent(
                        InstallationPermanentFailure.READ_BACK_MISMATCH,
                        fixture.store.clearForConfirmedRecovery(),
                    )
                } else {
                    fixture.trace.failures["remove.$SLOT.BASE"] = IOException()
                    temporary(InstallationTemporaryFailure.IO_FAILURE, fixture.store.clearForConfirmedRecovery())
                }
                assertNotNull(fixture.io.get(SLOT))
                fixture.io.silentDelete = null
                assertEquals(PendingClearResult.Cleared, fixture.store.clearForConfirmedRecovery())
                for (variant in AndroidCredentialVariant.entries) assertNull(fixture.io.get(SLOT, variant))
                assertTrue(fixture.inventory().isEmpty)
                assertContentEquals(credential, fixture.io.get(AndroidCredentialSlot.CREDENTIAL))
                assertContentEquals(marker, fixture.io.get(AndroidCredentialSlot.MARKER))
                assertTrue(fixture.io.readLimits.all { it.first == SLOT })
                assertFalse(
                    fixture.trace.events.any { it.startsWith("remove.CREDENTIAL.") || it.startsWith("remove.MARKER.") },
                )
            }
        }

    @Test
    fun cancellationPreservesOriginalIdentityResourcesAndLockRelease() =
        runTest {
            for (event in listOf("write.sync", "finish.after", "secondary")) {
                val fixture = Fixture()
                fixture.seed(slot(1))
                val original = CancellationException("pending-original")
                fixture.trace.failures[if (event == "secondary") "write.sync" else event] = original
                if (event == "secondary") {
                    fixture.trace.failures["rollback"] = CancellationException("pending-secondary")
                }
                assertSame(
                    original,
                    assertFailsWith<CancellationException> { fixture.store.replace(slot(1), slot(1, byteArrayOf(7))) },
                )
                assertEquals(0, fixture.io.openWrites)
                assertEquals(if (event == "finish.after") 0 else 1, fixture.io.rollbacks)
                val expected = if (event == "finish.after") slot(1, byteArrayOf(7)) else slot(1)
                assertTrue(
                    fixture
                        .inventory()
                        .entries()
                        .single()
                        .sameAs(expected),
                )
            }
        }

    @Test
    fun twoAdapterInstancesShareOneReadModifyWriteLock() =
        runTest {
            val fixture = Fixture()
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            val gatedIo =
                object : AndroidCredentialFileIo by fixture.io {
                    override fun startWrite(slot: AndroidCredentialSlot): AndroidCredentialWrite {
                        entered.countDown()
                        check(release.await(5, TimeUnit.SECONDS))
                        return fixture.io.startWrite(slot)
                    }
                }
            val firstStore = AndroidPendingComplaintActionStore(gatedIo, Dispatchers.Default)
            val first = async(Dispatchers.Default) { firstStore.createIfMissing(slot(1)) }
            try {
                assertTrue(entered.await(5, TimeUnit.SECONDS))
                val second =
                    async(start = CoroutineStart.UNDISPATCHED) {
                        fixture.store.createIfMissing(slot(1, byteArrayOf(9)))
                    }
                assertFalse(second.isCompleted)
                release.countDown()
                assertEquals(PendingCreateResult.Stored, first.await())
                assertEquals(PendingCreateResult.AlreadyPresent, second.await())
                assertTrue(
                    fixture
                        .inventory()
                        .entries()
                        .single()
                        .sameAs(slot(1)),
                )
            } finally {
                release.countDown()
            }
        }

    private class Fixture {
        val trace = PrimitiveTrace()
        val io = MemoryFileIo(trace)
        val store = AndroidPendingComplaintActionStore(io, Dispatchers.Unconfined)

        fun seed(vararg slots: PendingComplaintSlot) = io.put(SLOT, frame(slots.toList()))

        suspend fun inventory(): PendingComplaintSnapshot =
            assertIs<PendingReadResult.Verified>(
                store.read(),
            ).snapshot
    }

    companion object {
        private const val HEADER_BYTES = 10
        private const val UUID_BYTES = 36
        private val SLOT = AndroidCredentialSlot.PENDING_ACTIONS
        private val NEW = AndroidCredentialVariant.NEW
        private val BACKUP = AndroidCredentialVariant.BACKUP

        private fun slot(
            number: Int,
            bytes: ByteArray = byteArrayOf(number.toByte()),
        ): PendingComplaintSlot =
            assertIs<InstallationValueResult.Valid<PendingComplaintSlot>>(
                PendingComplaintSlot.checked("abcdefab-cdef-4abc-8def-${number.toString().padStart(12, '0')}", bytes),
            ).value

        // Independent physical-format fixture: order and declarations remain caller-controlled for faults.
        private fun frame(
            slots: List<PendingComplaintSlot> = emptyList(),
            declaredCount: Int = slots.size,
            declaredLogical: Int = slots.sumOf { it.size },
            schema: Int = 1,
        ): ByteArray {
            val buffer =
                ByteBuffer
                    .allocate(HEADER_BYTES + slots.sumOf { UUID_BYTES + Short.SIZE_BYTES + it.size })
                    .order(ByteOrder.BIG_ENDIAN)
            buffer.put("KPAS".encodeToByteArray())
            buffer.put(schema.toByte())
            buffer.put(declaredCount.toByte())
            buffer.putInt(declaredLogical)
            for (slot in slots) {
                buffer.put(slot.id.encodeToByteArray())
                buffer.putShort(slot.size.toShort())
                buffer.put(slot.bytes())
            }
            return buffer.array()
        }

        private fun permanent(
            reason: InstallationPermanentFailure,
            result: Any,
        ) {
            assertEquals(reason, assertIs<InstallationStorageFailure.PermanentFailure>(result).reason)
        }

        private fun temporary(
            reason: InstallationTemporaryFailure,
            result: Any,
        ) {
            assertEquals(reason, assertIs<InstallationStorageFailure.TemporarilyUnavailable>(result).reason)
        }
    }
}
