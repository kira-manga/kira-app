package me.manga.kira.platform.storage

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.io.IOException
import java.nio.ByteBuffer
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
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
 * Actual adapter/file protocol with primitive faults. No Android filesystem or Keystore claims.
 * Keep the cohesive fault matrix and shared fixtures together; literal sizes/generations are test vectors.
 */
@Suppress("TooManyFunctions", "MagicNumber")
class AndroidInstallationCredentialStoreTest {
    @Test
    fun createsAndReadsBackEveryField() =
        runTest {
            val fixture = Fixture()
            val candidate = candidate()
            assertEquals(CredentialCreateResult.Stored, fixture.store.createIfMissing(candidate))
            assertTrue(fixture.present().sameAs(candidate))
            assertEquals(1, fixture.keys.creations)
            assertEquals(0, fixture.io.openWrites)
            assertTrue(fixture.trace.events.indexOf("write.sync") < fixture.trace.events.indexOf("finish"))
            assertTrue(fixture.trace.events.lastIndexOf("syncDirectory") > fixture.trace.events.indexOf("finish"))
            assertEquals(CleanupMarkerReadResult.Missing, fixture.store.readCleanupMarker())
        }

    @Test
    fun existingWinnerIsFullyReadAndNeverOverwritten() =
        runTest {
            val fixture = Fixture()
            assertEquals(CredentialCreateResult.Stored, fixture.store.createIfMissing(candidate()))
            val before = fixture.io.get(CREDENTIAL)
            assertEquals(CredentialCreateResult.AlreadyPresent, fixture.store.createIfMissing(candidate(SECRET_B)))
            assertContentEquals(before, fixture.io.get(CREDENTIAL))
            assertTrue(fixture.present().sameAs(candidate()))
            assertEquals(1, fixture.keys.creations)
        }

    @Test
    fun replacementUsesExactGenerationAndCheckedTransition() =
        runTest {
            val fixture = Fixture()
            val active = candidate()
            assertEquals(CredentialCreateResult.Stored, fixture.store.createIfMissing(active))
            val pending = pending(active)
            assertEquals(CredentialReplaceResult.Stored, fixture.store.replace(1, pending))
            assertTrue(fixture.present().sameAs(pending))
            assertEquals(1, fixture.keys.creations)
            assertEquals(CredentialReplaceResult.Stale, fixture.store.replace(1, pending))
        }

    @Test
    fun missingAndStaleNeverFallThroughToCreation() =
        runTest {
            val fixture = Fixture()
            assertEquals(CredentialReplaceResult.Missing, fixture.store.replace(1, pending()))
            assertEquals(0, fixture.keys.creations)
            fixture.seed(candidate())
            val before = fixture.io.get(CREDENTIAL)
            assertEquals(CredentialReplaceResult.Stale, fixture.store.replace(2, pending()))
            assertContentEquals(before, fixture.io.get(CREDENTIAL))
        }

    @Test
    fun refusesChangedSecretVersionScopeAndReverseTransition() =
        runTest {
            val fixture = Fixture()
            val active = candidate()
            fixture.seed(active)
            val changedScope =
                value(InstallationCredentialMaterial.checked(INSTALLATION_ID, SECRET_A, "ANDROID", DELETION_KEY))
            val replacements =
                listOf(
                    pending(candidate(SECRET_B)),
                    checkedRecord(active.material, version = 2),
                    checkedRecord(changedScope),
                    checkedRecord(active.material, state = InstallationCredentialState.ACTIVE),
                )
            for (replacement in replacements) {
                permanent(InstallationPermanentFailure.STATE_CHANGED, fixture.store.replace(1, replacement))
                assertTrue(fixture.present().sameAs(active))
            }
            val pending = pending(active)
            assertEquals(CredentialReplaceResult.Stored, fixture.store.replace(1, pending))
            permanent(
                InstallationPermanentFailure.STATE_CHANGED,
                fixture.store.replace(2, checkedRecord(active.material, generation = 3)),
            )
        }

    @Test
    fun refusesOverflowNonInitialCreationAndOtherPlatform() =
        runTest {
            val fixture = Fixture()
            permanent(InstallationPermanentFailure.STATE_CHANGED, fixture.store.createIfMissing(pending()))
            val ios = value(InstallationCredentialMaterial.checked(INSTALLATION_ID, SECRET_A, "IOS", LIVE_SCOPE))
            permanent(
                InstallationPermanentFailure.UNSUPPORTED,
                fixture.store.createIfMissing(InstallationCredentialRecord.candidate(ios)),
            )
            assertEquals(0, fixture.keys.creations)
            val maximum =
                checkedRecord(
                    candidate().material,
                    generation = Long.MAX_VALUE,
                    state = InstallationCredentialState.ACTIVE,
                )
            fixture.seed(maximum)
            val replacement = checkedRecord(maximum.material, generation = Long.MAX_VALUE)
            permanent(InstallationPermanentFailure.STATE_CHANGED, fixture.store.replace(Long.MAX_VALUE, replacement))
            assertTrue(fixture.present().sameAs(maximum))
        }

    @Test
    fun keyOnlyAndFileOnlyAreNotMissingOrOverwritePermission() =
        runTest {
            val keyOnly = Fixture()
            keyOnly.keys.create()
            permanent(InstallationPermanentFailure.CORRUPT, keyOnly.store.read())
            permanent(InstallationPermanentFailure.CORRUPT, keyOnly.store.createIfMissing(candidate()))
            val fileOnly = Fixture()
            fileOnly.seed(candidate())
            fileOnly.keys.present = false
            permanent(InstallationPermanentFailure.INVALIDATED, fileOnly.store.read())
            permanent(InstallationPermanentFailure.INVALIDATED, fileOnly.store.createIfMissing(candidate(SECRET_B)))
            assertEquals(1, fileOnly.keys.creations)
        }

    @Test
    fun failedProbesCannotProveAbsence() =
        runTest {
            val fixture = Fixture()
            fixture.trace.failures["exists.CREDENTIAL.BASE"] = IOException()
            temporary(InstallationTemporaryFailure.IO_FAILURE, fixture.store.read())
            fixture.trace.failures["key.exists"] = problem(InstallationTemporaryFailure.LOCKED)
            temporary(InstallationTemporaryFailure.LOCKED, fixture.store.createIfMissing(candidate()))
            fixture.trace.failures["prepare"] = problem(InstallationPermanentFailure.UNSUPPORTED)
            permanent(InstallationPermanentFailure.UNSUPPORTED, fixture.store.read())
            assertEquals(0, fixture.keys.creations)
        }

    @Test
    fun rejectsTruncatedHeaderAndAuthenticationDamage() =
        runTest {
            val mutations: List<(ByteArray) -> ByteArray> =
                listOf(
                    { it.copyOf(8) },
                    { it.also { bytes -> bytes[0] = 0 } },
                    { it.also { bytes -> bytes[bytes.lastIndex] = (bytes.last().toInt() xor 1).toByte() } },
                )
            for (mutation in mutations) {
                val fixture = Fixture()
                fixture.seed(candidate())
                fixture.io.put(CREDENTIAL, mutation(assertNotNull(fixture.io.get(CREDENTIAL))))
                permanent(InstallationPermanentFailure.CORRUPT, fixture.store.read())
                permanent(InstallationPermanentFailure.CORRUPT, fixture.store.createIfMissing(candidate()))
            }
        }

    @Test
    fun unsupportedEnvelopeIsNotUnreadableResetAuthority() =
        runTest {
            val fixture = Fixture()
            fixture.seed(candidate())
            val bytes = assertNotNull(fixture.io.get(CREDENTIAL)).also { it[4] = 2 }
            fixture.io.put(CREDENTIAL, bytes)
            permanent(InstallationPermanentFailure.UNSUPPORTED, fixture.store.read())
            permanent(
                InstallationPermanentFailure.UNSUPPORTED,
                fixture.store.createCleanupMarkerIfMissing(unreadableMarker()),
            )
            assertEquals(0, fixture.keys.deletions)
        }

    @Test
    fun physicalAndPlaintextCeilingsAreCheckedBeforeDecryption() =
        runTest {
            for (size in listOf(4097, 5 + 12 + 16 + 2049)) {
                val fixture = Fixture()
                fixture.seed(candidate())
                fixture.io.put(CREDENTIAL, assertNotNull(fixture.io.get(CREDENTIAL)).copyOf(size))
                permanent(InstallationPermanentFailure.TOO_LARGE, fixture.store.read())
                assertEquals(0, fixture.keys.decryptions)
                assertTrue(fixture.io.readLimits.all { it.second == it.first.byteLimit })
            }
        }

    @Test
    fun rejectsWrongOrRepeatedProviderIvBeforeStartingAWrite() =
        runTest {
            val wrong = Fixture()
            wrong.keys.wrongIv = true
            permanent(InstallationPermanentFailure.READ_BACK_MISMATCH, wrong.store.createIfMissing(candidate()))
            assertFalse("start.CREDENTIAL" in wrong.trace.events)
            val repeated = Fixture()
            repeated.seed(candidate())
            repeated.keys.repeatIv = true
            permanent(InstallationPermanentFailure.READ_BACK_MISMATCH, repeated.store.replace(1, pending()))
            assertTrue(repeated.present().sameAs(candidate()))
        }

    @Test
    fun readBackChecksSecretNotOnlyPublicBinding() =
        runTest {
            val fixture = Fixture()
            fixture.io.afterFinish = { fixture.keys.decryptedOverride = encoded(candidate(SECRET_B)) }
            permanent(InstallationPermanentFailure.READ_BACK_MISMATCH, fixture.store.createIfMissing(candidate()))
            assertEquals(0, fixture.io.rollbacks)
        }

    @Test
    fun silentFinishCannotReportStoredOrRollBackAfterAttempt() =
        runTest {
            val fixture = Fixture()
            fixture.seed(candidate())
            val before = fixture.io.get(CREDENTIAL)
            fixture.io.silentFinish = true
            permanent(InstallationPermanentFailure.READ_BACK_MISMATCH, fixture.store.replace(1, pending()))
            assertContentEquals(before, fixture.io.get(CREDENTIAL))
            assertNotNull(fixture.io.get(CREDENTIAL, NEW))
            assertEquals(0, fixture.io.rollbacks)
            assertTrue(fixture.present().sameAs(candidate()))
            assertNull(fixture.io.get(CREDENTIAL, NEW))
        }

    @Test
    fun silentFinishCloseFailureIsDetectedAndOwnedStreamIsClosed() =
        runTest {
            val fixture = Fixture()
            fixture.io.finishKeepsOpen = true
            permanent(InstallationPermanentFailure.READ_BACK_MISMATCH, fixture.store.createIfMissing(candidate()))
            assertEquals(0, fixture.io.openWrites)
            assertEquals(0, fixture.io.rollbacks)
            assertTrue("close" in fixture.trace.events)
        }

    @Test
    fun failedFdSyncRollsBackAndVerifiesTheExactOldBase() =
        runTest {
            val fixture = Fixture()
            fixture.seed(candidate())
            val before = fixture.io.get(CREDENTIAL)
            fixture.trace.failures["write.sync"] = IOException()
            temporary(InstallationTemporaryFailure.IO_FAILURE, fixture.store.replace(1, pending()))
            assertContentEquals(before, fixture.io.get(CREDENTIAL))
            assertNull(fixture.io.get(CREDENTIAL, NEW))
            assertEquals(1, fixture.io.rollbacks)
            assertEquals(0, fixture.io.openWrites)
            assertTrue(fixture.present().sameAs(candidate()))
        }

    @Test
    fun silentRollbackFailureIsUncertainAndPreservesOrphanEvidence() =
        runTest {
            val fixture = Fixture()
            fixture.io.silentRollback = true
            fixture.trace.failures["write.sync"] = IOException()
            temporary(InstallationTemporaryFailure.UNCERTAIN, fixture.store.createIfMissing(candidate()))
            assertNotNull(fixture.io.get(CREDENTIAL, NEW))
            assertEquals(0, fixture.io.openWrites)
            permanent(InstallationPermanentFailure.CORRUPT, fixture.store.read())
        }

    @Test
    fun postCommitDirectorySyncFailureNeverReportsStoredOrRollsBack() =
        runTest {
            val fixture = Fixture()
            fixture.io.afterFinish = { fixture.trace.failures["syncDirectory"] = IOException() }
            temporary(InstallationTemporaryFailure.IO_FAILURE, fixture.store.createIfMissing(candidate()))
            assertEquals(0, fixture.io.rollbacks)
            assertTrue(fixture.present().sameAs(candidate()))
        }

    @Test
    fun cancellationBeforeFinishPreservesIdentityAndReleasesTheMutex() =
        runTest {
            val fixture = Fixture()
            fixture.seed(candidate())
            val cancellation = CancellationException("host fault")
            fixture.trace.failures["write.sync"] = cancellation
            val caught = assertFailsWith<CancellationException> { fixture.store.replace(1, pending()) }
            assertSame(cancellation, caught)
            assertEquals(1, fixture.io.rollbacks)
            assertEquals(0, fixture.io.openWrites)
            assertTrue(fixture.present().sameAs(candidate()))
        }

    @Test
    fun cancellationAfterFinishNeverBlindlyRollsBack() =
        runTest {
            val fixture = Fixture()
            val cancellation = CancellationException("host fault")
            fixture.trace.failures["finish.after"] = cancellation
            val caught = assertFailsWith<CancellationException> { fixture.store.createIfMissing(candidate()) }
            assertSame(cancellation, caught)
            assertEquals(0, fixture.io.rollbacks)
            assertEquals(0, fixture.io.openWrites)
            assertTrue(fixture.present().sameAs(candidate()))
        }

    @Test
    fun exactMarkerResumesButConflictingMarkerIsPreserved() =
        runTest {
            val fixture = Fixture()
            fixture.seed(pending())
            val marker = marker()
            assertEquals(CleanupMarkerCreateResult.Stored, fixture.store.createCleanupMarkerIfMissing(marker))
            assertEquals(CleanupMarkerCreateResult.AlreadyPresent, fixture.store.createCleanupMarkerIfMissing(marker))
            permanent(
                InstallationPermanentFailure.MARKER_CONFLICT,
                fixture.store.createCleanupMarkerIfMissing(unreadableMarker()),
            )
            assertTrue(
                assertIs<CleanupMarkerReadResult.Present>(fixture.store.readCleanupMarker()).marker.sameAs(marker),
            )
            permanent(InstallationPermanentFailure.MARKER_CONFLICT, fixture.store.createIfMissing(candidate()))
        }

    @Test
    fun malformedOrOversizedMarkerIsNeverMissingOrOverwritten() =
        runTest {
            for (bytes in listOf(byteArrayOf(0), ByteArray(513))) {
                val fixture = Fixture()
                fixture.io.put(MARKER, bytes)
                assertIs<InstallationStorageFailure.PermanentFailure>(fixture.store.readCleanupMarker())
                assertIs<InstallationStorageFailure.PermanentFailure>(
                    fixture.store.createCleanupMarkerIfMissing(unreadableMarker()),
                )
                assertContentEquals(bytes, fixture.io.get(MARKER))
                assertEquals(0, fixture.keys.deletions)
            }
        }

    @Test
    fun loneOrDifferentPendingMarkerCannotAuthorizeCleanup() =
        runTest {
            val fixture = Fixture()
            val expected = unreadableMarker()
            fixture.io.put(MARKER, encoded(expected), NEW)
            permanent(InstallationPermanentFailure.MARKER_CONFLICT, fixture.store.readCleanupMarker())
            permanent(InstallationPermanentFailure.MARKER_CONFLICT, fixture.store.finishMarkedCleanup(expected))
            fixture.io.put(MARKER, encoded(marker()))
            permanent(InstallationPermanentFailure.MARKER_CONFLICT, fixture.store.readCleanupMarker())
            assertContentEquals(encoded(expected), fixture.io.get(MARKER, NEW))
            assertEquals(0, fixture.keys.deletions)
        }

    @Test
    fun exactPendingMarkerCanBeDiscardedOnlyAfterValidatedBase() =
        runTest {
            val fixture = Fixture()
            val expected = unreadableMarker()
            fixture.io.put(MARKER, encoded(expected))
            fixture.io.put(MARKER, encoded(expected), NEW)
            val read = assertIs<CleanupMarkerReadResult.Present>(fixture.store.readCleanupMarker())
            assertTrue(read.marker.sameAs(expected))
            assertNull(fixture.io.get(MARKER, NEW))
        }

    @Test
    fun invalidOldCredentialAndLegacyBackupNeverPermitVariantDiscard() =
        runTest {
            val fixture = Fixture()
            fixture.seed(candidate())
            fixture.io.put(CREDENTIAL, byteArrayOf(0))
            fixture.io.put(CREDENTIAL, byteArrayOf(1), NEW)
            permanent(InstallationPermanentFailure.CORRUPT, fixture.store.read())
            assertNotNull(fixture.io.get(CREDENTIAL, NEW))
            fixture.io.put(CREDENTIAL, byteArrayOf(2), BACKUP)
            permanent(InstallationPermanentFailure.CORRUPT, fixture.store.createIfMissing(candidate()))
            assertFalse("start.CREDENTIAL" in fixture.trace.events)
            assertNotNull(fixture.io.get(CREDENTIAL, BACKUP))
        }

    @Test
    fun newMarkerMustMatchReadablePendingGenerationAndReason() =
        runTest {
            val fixture = Fixture()
            fixture.seed(pending())
            val wrong = listOf(unreadableMarker(), marker(3), marker(2, CredentialCleanupReason.USER_RESET_CONFIRMED))
            for (marker in wrong) {
                permanent(
                    InstallationPermanentFailure.STATE_CHANGED,
                    fixture.store.createCleanupMarkerIfMissing(marker),
                )
                assertEquals(CleanupMarkerReadResult.Missing, fixture.store.readCleanupMarker())
            }
            val missing = Fixture()
            permanent(InstallationPermanentFailure.STATE_CHANGED, missing.store.createCleanupMarkerIfMissing(marker()))
            assertEquals(
                CleanupMarkerCreateResult.Stored,
                missing.store.createCleanupMarkerIfMissing(unreadableMarker()),
            )
        }

    @Test
    fun keyDeletedFileFailureResumesUnderRetainedExactMarker() =
        runTest {
            val fixture = Fixture()
            fixture.seed(pending())
            val expected = marker()
            assertEquals(CleanupMarkerCreateResult.Stored, fixture.store.createCleanupMarkerIfMissing(expected))
            fixture.trace.failures["remove.CREDENTIAL.BASE"] = IOException()
            temporary(InstallationTemporaryFailure.IO_FAILURE, fixture.store.finishMarkedCleanup(expected))
            assertFalse(fixture.keys.present)
            assertNotNull(fixture.io.get(CREDENTIAL))
            assertTrue(
                assertIs<CleanupMarkerReadResult.Present>(fixture.store.readCleanupMarker()).marker.sameAs(expected),
            )
            assertEquals(CredentialDeleteResult.Deleted, fixture.store.finishMarkedCleanup(expected))
            assertEquals(CredentialReadResult.Missing, fixture.store.read())
            assertNotNull(fixture.io.get(MARKER))
        }

    @Test
    fun silentKeyOrFileDeletionIsNotSuccessAndLeavesMarker() =
        runTest {
            for (silentKey in listOf(true, false)) {
                val fixture = Fixture()
                fixture.seed(pending())
                val expected = marker()
                assertEquals(CleanupMarkerCreateResult.Stored, fixture.store.createCleanupMarkerIfMissing(expected))
                fixture.keys.silentDelete = silentKey
                if (!silentKey) fixture.io.silentDelete = CREDENTIAL to BASE
                permanent(InstallationPermanentFailure.READ_BACK_MISMATCH, fixture.store.finishMarkedCleanup(expected))
                assertNotNull(fixture.io.get(MARKER))
                assertNotNull(fixture.io.get(CREDENTIAL))
            }
        }

    @Test
    fun nullMarkerNeverDeletesAReadableCredentialEvenIfInjected() =
        runTest {
            val fixture = Fixture()
            fixture.seed(candidate())
            val expected = unreadableMarker()
            fixture.io.put(MARKER, encoded(expected))
            assertEquals(CredentialDeleteResult.Stale, fixture.store.finishMarkedCleanup(expected))
            permanent(
                InstallationPermanentFailure.STATE_CHANGED,
                fixture.store.resetUnreadableAfterConfirmation(expected),
            )
            assertEquals(0, fixture.keys.deletions)
            assertTrue(fixture.present().sameAs(candidate()))
        }

    @Test
    fun staleOrUnmarkedDeletionPreservesReadableState() =
        runTest {
            val fixture = Fixture()
            fixture.seed(pending())
            val expected = marker()
            assertEquals(CredentialDeleteResult.Stale, fixture.store.delete(2, expected))
            assertEquals(CleanupMarkerCreateResult.Stored, fixture.store.createCleanupMarkerIfMissing(expected))
            assertEquals(CredentialDeleteResult.Stale, fixture.store.delete(3, expected))
            assertEquals(CredentialDeleteResult.Stale, fixture.store.finishMarkedCleanup(marker(3)))
            fixture.io.put(MARKER, encoded(marker(2, CredentialCleanupReason.USER_RESET_CONFIRMED)))
            assertEquals(
                CredentialDeleteResult.Stale,
                fixture.store.finishMarkedCleanup(marker(2, CredentialCleanupReason.USER_RESET_CONFIRMED)),
            )
            assertEquals(0, fixture.keys.deletions)
        }

    @Test
    fun temporaryOrProtectionFailureNeverAuthorizesMarkedDeletion() =
        runTest {
            val fixture = Fixture()
            fixture.seed(pending())
            val expected = marker()
            assertEquals(CleanupMarkerCreateResult.Stored, fixture.store.createCleanupMarkerIfMissing(expected))
            fixture.trace.failures["key.exists"] = problem(InstallationTemporaryFailure.LOCKED)
            temporary(InstallationTemporaryFailure.LOCKED, fixture.store.finishMarkedCleanup(expected))
            fixture.trace.failures["exists.CREDENTIAL.BASE"] = problem(InstallationPermanentFailure.UNSUPPORTED)
            permanent(InstallationPermanentFailure.UNSUPPORTED, fixture.store.finishMarkedCleanup(expected))
            assertEquals(0, fixture.keys.deletions)
            assertNotNull(fixture.io.get(CREDENTIAL))
        }

    @Test
    fun markerRemovalRequiresCredentialAbsenceAndVerifiedMarkerAbsence() =
        runTest {
            val fixture = Fixture()
            fixture.seed(pending())
            val expected = marker()
            assertEquals(CleanupMarkerCreateResult.Stored, fixture.store.createCleanupMarkerIfMissing(expected))
            permanent(InstallationPermanentFailure.READ_BACK_MISMATCH, fixture.store.removeCleanupMarker(expected))
            assertEquals(CredentialDeleteResult.Deleted, fixture.store.delete(2, expected))
            assertEquals(CredentialReadResult.Missing, fixture.store.read())
            assertEquals(CredentialDeleteResult.Missing, fixture.store.finishMarkedCleanup(expected))
            permanent(InstallationPermanentFailure.MARKER_CONFLICT, fixture.store.createIfMissing(candidate(SECRET_B)))
            assertEquals(CleanupMarkerRemoveResult.Stale, fixture.store.removeCleanupMarker(unreadableMarker()))
            fixture.io.silentDelete = MARKER to BASE
            permanent(InstallationPermanentFailure.READ_BACK_MISMATCH, fixture.store.removeCleanupMarker(expected))
            fixture.io.silentDelete = null
            assertEquals(CleanupMarkerRemoveResult.Removed, fixture.store.removeCleanupMarker(expected))
            assertEquals(CleanupMarkerRemoveResult.Missing, fixture.store.removeCleanupMarker(expected))
            assertEquals(CredentialCreateResult.Stored, fixture.store.createIfMissing(candidate(SECRET_B)))
        }

    @Test
    fun warnedUnreadableResetCanRemoveInvalidatedOrphanKeyAndEveryVariant() =
        runTest {
            val fixture = Fixture()
            fixture.keys.create()
            fixture.keys.invalidated = true
            val expected = unreadableMarker()
            assertEquals(CleanupMarkerCreateResult.Stored, fixture.store.createCleanupMarkerIfMissing(expected))
            fixture.io.put(CREDENTIAL, byteArrayOf(1), NEW)
            fixture.io.put(CREDENTIAL, byteArrayOf(2), BACKUP)
            assertEquals(CredentialResetResult.Deleted, fixture.store.resetUnreadableAfterConfirmation(expected))
            assertEquals(CredentialReadResult.Missing, fixture.store.read())
            assertEquals(CredentialResetResult.Missing, fixture.store.resetUnreadableAfterConfirmation(expected))
            assertNotNull(fixture.io.get(MARKER))
        }

    @Test
    fun twoAdapterInstancesShareOneProcessLockAndOneWinningKey() =
        runTest {
            val fixture = Fixture()
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            fixture.keys.beforeCreate = {
                entered.countDown()
                check(release.await(5, TimeUnit.SECONDS))
            }
            val firstStore = AndroidInstallationCredentialStore(fixture.keys, fixture.io, Dispatchers.Default)
            val first = async(Dispatchers.Default) { firstStore.createIfMissing(candidate()) }
            try {
                assertTrue(entered.await(5, TimeUnit.SECONDS))
                val second =
                    async(start = CoroutineStart.UNDISPATCHED) { fixture.store.createIfMissing(candidate(SECRET_B)) }
                assertFalse(second.isCompleted)
                release.countDown()
                assertEquals(CredentialCreateResult.Stored, first.await())
                assertEquals(CredentialCreateResult.AlreadyPresent, second.await())
                assertEquals(1, fixture.keys.creations)
                assertTrue(fixture.present().sameAs(candidate()))
            } finally {
                release.countDown()
            }
        }

    private class Fixture {
        val trace = PrimitiveTrace()
        val io = MemoryFileIo(trace)
        val keys = MemoryKeyApi(trace)
        val store = AndroidInstallationCredentialStore(keys, io, Dispatchers.Unconfined)

        fun seed(record: InstallationCredentialRecord) {
            keys.create()
            val plaintext = encoded(record)
            io.put(CREDENTIAL, AndroidCredentialEnvelope.pack(keys.encrypt(plaintext), plaintext.size, null))
            plaintext.fill(0)
        }

        suspend fun present(): InstallationCredentialRecord =
            assertIs<CredentialReadResult.Present>(
                store.read(),
            ).record
    }

    /** Faults are at primitive boundaries, not a second implementation of the lifecycle. */
    internal class PrimitiveTrace {
        val events: MutableList<String> = Collections.synchronizedList(mutableListOf())
        val failures = ConcurrentHashMap<String, Exception>()

        fun hit(event: String) {
            events.add(event)
            failures.remove(event)?.let { throw it }
        }
    }

    internal class MemoryFileIo(
        private val trace: PrimitiveTrace,
    ) : AndroidCredentialFileIo {
        private val files = ConcurrentHashMap<Pair<AndroidCredentialSlot, AndroidCredentialVariant>, ByteArray>()
        val readLimits = mutableListOf<Pair<AndroidCredentialSlot, Int>>()
        var silentFinish = false
        var finishKeepsOpen = false
        var silentRollback = false
        var silentDelete: Pair<AndroidCredentialSlot, AndroidCredentialVariant>? = null
        var afterFinish: (() -> Unit)? = null
        var openWrites = 0
        var rollbacks = 0

        fun put(
            slot: AndroidCredentialSlot,
            bytes: ByteArray,
            variant: AndroidCredentialVariant = BASE,
        ) {
            files[slot to variant] = bytes.copyOf()
        }

        fun get(
            slot: AndroidCredentialSlot,
            variant: AndroidCredentialVariant = BASE,
        ): ByteArray? = files[slot to variant]?.copyOf()

        override fun prepareDirectory() = trace.hit("prepare")

        override fun exists(
            slot: AndroidCredentialSlot,
            variant: AndroidCredentialVariant,
        ): Boolean {
            trace.hit("exists.$slot.$variant")
            return files.containsKey(slot to variant)
        }

        override fun readBounded(
            slot: AndroidCredentialSlot,
            variant: AndroidCredentialVariant,
            limit: Int,
        ): ByteArray? {
            trace.hit("read.$slot.$variant")
            readLimits.add(slot to limit)
            val bytes = files[slot to variant] ?: return null
            if (bytes.size > limit) androidCredentialPermanent(InstallationPermanentFailure.TOO_LARGE)
            return bytes.copyOf()
        }

        override fun syncFile(slot: AndroidCredentialSlot) {
            trace.hit("syncFile.$slot")
            if (!files.containsKey(slot to BASE)) throw IOException()
        }

        override fun syncDirectory() = trace.hit("syncDirectory")

        override fun startWrite(slot: AndroidCredentialSlot): AndroidCredentialWrite {
            trace.hit("start.$slot")
            files[slot to NEW] = byteArrayOf()
            openWrites++
            return MemoryWrite(slot)
        }

        override fun remove(
            slot: AndroidCredentialSlot,
            variant: AndroidCredentialVariant,
        ) {
            trace.hit("remove.$slot.$variant")
            if (silentDelete != (slot to variant)) files.remove(slot to variant)
        }

        private inner class MemoryWrite(
            private val slot: AndroidCredentialSlot,
        ) : AndroidCredentialWrite {
            private var closed = false

            override fun write(bytes: ByteArray) {
                trace.hit("write")
                files[slot to NEW] = bytes.copyOf()
            }

            override fun sync() = trace.hit("write.sync")

            override fun finish() {
                trace.hit("finish")
                if (!silentFinish) files[slot to BASE] = checkNotNull(files.remove(slot to NEW))
                if (!finishKeepsOpen) markClosed()
                afterFinish?.also { afterFinish = null }?.invoke()
                trace.hit("finish.after")
            }

            override fun rollback() {
                rollbacks++
                trace.hit("rollback")
                if (!silentRollback) files.remove(slot to NEW)
                markClosed()
            }

            override fun isClosed(): Boolean = closed

            override fun close() {
                trace.hit("close")
                markClosed()
            }

            private fun markClosed() {
                if (!closed) openWrites--
                closed = true
            }
        }
    }

    /** Host JCE authenticates test bytes only; this never opens a production namespace/provider. */
    private class MemoryKeyApi(
        private val trace: PrimitiveTrace,
    ) : AndroidCredentialKeyApi {
        var present = false
        var invalidated = false
        var creations = 0
        var deletions = 0
        var decryptions = 0
        var wrongIv = false
        var repeatIv = false
        var silentDelete = false
        var decryptedOverride: ByteArray? = null
        var beforeCreate: (() -> Unit)? = null
        private var nonce = 0
        private val key = SecretKeySpec(ByteArray(32) { 1 }, "AES")
        private val aad = "host-fault-test-only".encodeToByteArray()

        override fun exists(): Boolean {
            trace.hit("key.exists")
            if (present && invalidated) androidCredentialPermanent(InstallationPermanentFailure.INVALIDATED)
            return present
        }

        override fun create() {
            trace.hit("key.create")
            beforeCreate?.invoke()
            check(!present)
            creations++
            present = true
        }

        override fun encrypt(plaintext: ByteArray): AndroidCredentialCiphertext {
            trace.hit("key.encrypt")
            check(present)
            if (!repeatIv) nonce++
            val iv = ByteBuffer.allocate(12).putInt(8, nonce).array()
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, iv))
            cipher.updateAAD(aad)
            return AndroidCredentialCiphertext(if (wrongIv) iv.copyOf(8) else iv, cipher.doFinal(plaintext))
        }

        override fun decrypt(encrypted: AndroidCredentialCiphertext): ByteArray {
            trace.hit("key.decrypt")
            decryptions++
            check(present)
            decryptedOverride?.let { return it.copyOf() }
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, encrypted.iv))
            cipher.updateAAD(aad)
            return cipher.doFinal(encrypted.ciphertext)
        }

        override fun delete() {
            trace.hit("key.delete")
            deletions++
            if (!silentDelete) present = false
        }
    }

    companion object {
        private val CREDENTIAL = AndroidCredentialSlot.CREDENTIAL
        private val MARKER = AndroidCredentialSlot.MARKER
        private val BASE = AndroidCredentialVariant.BASE
        private val NEW = AndroidCredentialVariant.NEW
        private val BACKUP = AndroidCredentialVariant.BACKUP
        private const val INSTALLATION_ID = "12345678-1234-4234-8234-123456789abc"
        private const val DELETION_KEY = "abcdefab-cdef-4abc-8def-abcdefabcdef"
        private const val LIVE_SCOPE = "00000000-0000-0000-0000-000000000000"
        private const val SECRET_A = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"
        private const val SECRET_B = "BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBA"

        private fun candidate(secret: String = SECRET_A): InstallationCredentialRecord =
            InstallationCredentialRecord.candidate(
                value(InstallationCredentialMaterial.checked(INSTALLATION_ID, secret, "ANDROID", LIVE_SCOPE)),
            )

        private fun pending(record: InstallationCredentialRecord = candidate()): InstallationCredentialRecord =
            value(record.beginDeletion(DELETION_KEY))

        private fun checkedRecord(
            material: InstallationCredentialMaterial,
            version: Long = 1,
            generation: Long = 2,
            state: InstallationCredentialState = InstallationCredentialState.DELETION_PENDING,
        ): InstallationCredentialRecord =
            value(
                InstallationCredentialRecord.checked(
                    1,
                    material,
                    version,
                    generation,
                    state,
                    if (state == InstallationCredentialState.DELETION_PENDING) DELETION_KEY else null,
                ),
            )

        private fun marker(
            generation: Long = 2,
            reason: CredentialCleanupReason = CredentialCleanupReason.SERVER_TERMINAL_CONFIRMED,
        ): CredentialCleanupMarker = value(CredentialCleanupMarker.checked(1, generation, reason))

        private fun unreadableMarker(): CredentialCleanupMarker =
            value(
                CredentialCleanupMarker.checked(1, null, CredentialCleanupReason.UNREADABLE_RESET_CONFIRMED),
            )

        private fun <T> value(result: InstallationValueResult<T>): T =
            assertIs<InstallationValueResult.Valid<T>>(
                result,
            ).value

        private fun encoded(record: InstallationCredentialRecord): ByteArray =
            assertIs<InstallationCodecResult.Value<InstallationEncodedPayload>>(
                InstallationCredentialCodec.encode(record),
            ).value.copyBytes()

        private fun encoded(marker: CredentialCleanupMarker): ByteArray =
            assertIs<InstallationCodecResult.Value<InstallationEncodedPayload>>(
                CredentialCleanupMarkerCodec.encode(marker),
            ).value.copyBytes()

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

        private fun problem(reason: InstallationTemporaryFailure): AndroidCredentialProblem =
            AndroidCredentialProblem(InstallationStorageFailure.TemporarilyUnavailable(reason))

        private fun problem(reason: InstallationPermanentFailure): AndroidCredentialProblem =
            AndroidCredentialProblem(InstallationStorageFailure.PermanentFailure(reason))
    }
}
