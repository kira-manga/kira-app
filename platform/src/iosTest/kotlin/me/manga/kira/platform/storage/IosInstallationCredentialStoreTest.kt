@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)

package me.manga.kira.platform.storage

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.pointed
import kotlinx.cinterop.ptr
import kotlinx.cinterop.readBytes
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import platform.CoreFoundation.CFArrayAppendValue
import platform.CoreFoundation.CFArrayCreateMutable
import platform.CoreFoundation.CFDataCreate
import platform.CoreFoundation.CFDataGetBytePtr
import platform.CoreFoundation.CFDataGetLength
import platform.CoreFoundation.CFDataGetTypeID
import platform.CoreFoundation.CFDataRef
import platform.CoreFoundation.CFDictionaryCreateMutable
import platform.CoreFoundation.CFDictionaryGetValue
import platform.CoreFoundation.CFDictionaryRef
import platform.CoreFoundation.CFDictionarySetValue
import platform.CoreFoundation.CFGetTypeID
import platform.CoreFoundation.CFMutableDictionaryRef
import platform.CoreFoundation.CFNumberGetValue
import platform.CoreFoundation.CFRelease
import platform.CoreFoundation.CFRetain
import platform.CoreFoundation.CFStringCreateWithCString
import platform.CoreFoundation.CFStringGetCString
import platform.CoreFoundation.CFStringGetLength
import platform.CoreFoundation.CFStringRef
import platform.CoreFoundation.CFTypeRefVar
import platform.CoreFoundation.kCFBooleanFalse
import platform.CoreFoundation.kCFBooleanTrue
import platform.CoreFoundation.kCFNumberIntType
import platform.CoreFoundation.kCFStringEncodingUTF8
import platform.CoreFoundation.kCFTypeArrayCallBacks
import platform.CoreFoundation.kCFTypeDictionaryKeyCallBacks
import platform.CoreFoundation.kCFTypeDictionaryValueCallBacks
import platform.Foundation.CFBridgingRelease
import platform.Foundation.NSProcessInfo
import platform.Foundation.NSUUID
import platform.LocalAuthentication.LAContext
import platform.Security.errSecAuthFailed
import platform.Security.errSecDecode
import platform.Security.errSecDuplicateItem
import platform.Security.errSecIO
import platform.Security.errSecInteractionNotAllowed
import platform.Security.errSecInteractionRequired
import platform.Security.errSecItemNotFound
import platform.Security.errSecMissingEntitlement
import platform.Security.errSecNotAvailable
import platform.Security.errSecParam
import platform.Security.errSecSuccess
import platform.Security.errSecUnimplemented
import platform.Security.kSecAttrAccessGroup
import platform.Security.kSecAttrAccessible
import platform.Security.kSecAttrAccessibleAfterFirstUnlock
import platform.Security.kSecAttrAccessibleWhenUnlockedThisDeviceOnly
import platform.Security.kSecAttrAccount
import platform.Security.kSecAttrService
import platform.Security.kSecAttrSynchronizable
import platform.Security.kSecAttrSynchronizableAny
import platform.Security.kSecClass
import platform.Security.kSecClassGenericPassword
import platform.Security.kSecMatchLimit
import platform.Security.kSecReturnAttributes
import platform.Security.kSecReturnData
import platform.Security.kSecUseAuthenticationContext
import platform.Security.kSecValueData
import kotlin.test.Ignore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Native-target unit tests call the real adapter against four state-retaining SecItem primitives. */
class IosInstallationCredentialStoreTest {
    @Test
    fun invalidGroupAndNonisolatedTestNamespaceNeverReachSecurity() =
        runTest {
            for (group in listOf("", " ", "*.me.manga.kira", "group..app", "a".repeat(256))) {
                val api = KeychainFake()
                val store = store(api, group)
                failure(store.read(), InstallationPermanentFailure.UNSUPPORTED)
                failure(store.createIfMissing(candidate()), InstallationPermanentFailure.UNSUPPORTED)
                assertTrue(api.calls.isEmpty())
            }
            assertFailsWith<IllegalArgumentException> {
                IosInstallationCredentialStore.isolatedForTest(GROUP, PRODUCTION_SERVICE, KeychainFake())
            }
            assertFailsWith<IllegalArgumentException> {
                IosInstallationCredentialStore.isolatedForTest(
                    GROUP,
                    "$PRODUCTION_SERVICE.test.not-a-uuid",
                    KeychainFake(),
                )
            }
        }

    @Test
    fun onlyNativeNotFoundIsMissingAndStatusesStayContentFree() =
        runTest {
            val api = KeychainFake()
            val store = store(api)
            assertEquals(CredentialReadResult.Missing, store.read())
            val temporary =
                mapOf(
                    errSecInteractionNotAllowed to InstallationTemporaryFailure.LOCKED,
                    errSecInteractionRequired to InstallationTemporaryFailure.LOCKED,
                    errSecIO to InstallationTemporaryFailure.IO_FAILURE,
                    errSecNotAvailable to InstallationTemporaryFailure.IO_FAILURE,
                    errSecAuthFailed to InstallationTemporaryFailure.UNCERTAIN,
                    -123456 to InstallationTemporaryFailure.UNCERTAIN,
                )
            for ((status, reason) in temporary) {
                api.copyFailure = { _, _ -> status }
                unavailable(store.read(), reason)
            }
            for (status in listOf(errSecUnimplemented, errSecMissingEntitlement, errSecParam)) {
                api.copyFailure = { _, _ -> status }
                failure(store.read(), InstallationPermanentFailure.UNSUPPORTED)
            }
            api.copyFailure = { _, _ -> errSecSuccess }
            failure(store.read(), InstallationPermanentFailure.STATE_CHANGED)
            assertTrue(api.calls.all { it.operation == "read" })
        }

    @Test
    fun createsCanonicalRecordReadsBackAndNeverOverwritesWinner() =
        runTest {
            val api = KeychainFake()
            val store = store(api)
            val original = candidate()
            assertEquals(CredentialCreateResult.Stored, store.createIfMissing(original))
            assertRecord(original, store.read())
            assertEquals(CredentialCreateResult.AlreadyPresent, store.createIfMissing(candidate(otherMaterial())))
            assertRecord(original, store.read())
            assertEquals(1, api.calls.count { it.operation == "add" })
            assertTrue(
                api.items
                    .single()
                    .bytes
                    .contentEquals(bytes(original)),
            )
        }

    @Test
    fun nativeDuplicateCreationReadsTheRacingWinnerWithoutUpdate() =
        runTest {
            val api = KeychainFake()
            val winner = candidate(otherMaterial())
            api.beforeAdd = { api.items += item(winner) }
            val store = store(api)
            assertEquals(CredentialCreateResult.AlreadyPresent, store.createIfMissing(candidate()))
            assertRecord(winner, store.read())
            assertFalse(api.calls.any { it.operation == "update" || it.operation == "delete" })
            assertEquals(1, api.items.size)
        }

    @Test
    fun duplicateCreationWithUnreadableWinnerFailsWithoutRepair() =
        runTest {
            val api = KeychainFake()
            api.beforeAdd = { api.items += FakeItem(CREDENTIAL, byteArrayOf()) }
            failure(store(api).createIfMissing(candidate()), InstallationPermanentFailure.CORRUPT)
            assertTrue(
                api.items
                    .single()
                    .bytes
                    .isEmpty(),
            )
            assertFalse(api.calls.any { it.operation == "update" || it.operation == "delete" })
        }

    @Test
    fun noninitialAndOtherPlatformCandidatesAreRefusedBeforeAnyCall() =
        runTest {
            val api = KeychainFake()
            val store = store(api)
            failure(store.createIfMissing(reset(candidate())), InstallationPermanentFailure.STATE_CHANGED)
            val android = material("ANDROID")
            failure(store.createIfMissing(candidate(android)), InstallationPermanentFailure.UNSUPPORTED)
            failure(store.replace(1, reset(candidate(android))), InstallationPermanentFailure.UNSUPPORTED)
            assertTrue(api.calls.isEmpty())
            api.items += item(candidate(android))
            failure(store.read(), InstallationPermanentFailure.UNSUPPORTED)
            failure(store.createIfMissing(candidate()), InstallationPermanentFailure.UNSUPPORTED)
            assertFalse(api.calls.any { it.operation != "read" })
        }

    @Test
    fun existingCorruptionAndMarkersBlockOrdinaryWrites() =
        runTest {
            val api = KeychainFake()
            val store = store(api)
            api.items += FakeItem(CREDENTIAL, "broken".encodeToByteArray())
            failure(store.createIfMissing(candidate()), InstallationPermanentFailure.CORRUPT)
            api.items.clear()
            api.items += item(candidate())
            api.items += markerItem(marker(2))
            failure(store.createIfMissing(candidate()), InstallationPermanentFailure.MARKER_CONFLICT)
            failure(store.replace(1, reset(candidate())), InstallationPermanentFailure.MARKER_CONFLICT)
            api.items.last().bytes = byteArrayOf()
            failure(store.replace(1, reset(candidate())), InstallationPermanentFailure.CORRUPT)
            assertFalse(api.calls.any { it.operation != "read" })
        }

    @Test
    fun generationReplacementPreservesAllMaterialAndNeverReactivates() =
        runTest {
            val api = KeychainFake()
            val original = candidate()
            val next = deleting(original)
            api.items += item(original)
            val store = store(api)
            assertEquals(CredentialReplaceResult.Stale, store.replace(2, next))
            assertEquals(CredentialReplaceResult.Stored, store.replace(1, next))
            assertRecord(next, store.read())
            failure(store.replace(2, record(generation = 3)), InstallationPermanentFailure.STATE_CHANGED)
            assertEquals(1, api.calls.count { it.operation == "update" })
            assertRecord(next, store.read())
        }

    @Test
    fun replacementRejectsIdentityVersionScopeAndGenerationSubstitution() =
        runTest {
            val original = candidate()
            val changes =
                listOf(
                    reset(candidate(otherMaterial())),
                    record(version = 2, generation = 2, state = InstallationCredentialState.LOCAL_RESET_PENDING),
                    record(generation = 3, state = InstallationCredentialState.LOCAL_RESET_PENDING),
                    record(generation = 2),
                    reset(candidate(material(scope = OTHER_SCOPE))),
                )
            for (changed in changes) {
                val api = KeychainFake().also { it.items += item(original) }
                failure(store(api).replace(1, changed), InstallationPermanentFailure.STATE_CHANGED)
                assertFalse(api.calls.any { it.operation != "read" })
            }
            val api = KeychainFake().also { it.items += item(record(generation = Long.MAX_VALUE)) }
            val overflow = record(generation = Long.MAX_VALUE, state = InstallationCredentialState.LOCAL_RESET_PENDING)
            failure(
                store(api).replace(Long.MAX_VALUE, overflow),
                InstallationPermanentFailure.STATE_CHANGED,
            )
        }

    @Test
    fun disappearedOrStaleNativeUpdateNeverFallsThroughToAdd() =
        runTest {
            val api = KeychainFake().also { it.items += item(candidate()) }
            val store = store(api)
            api.updateStatus = errSecItemNotFound
            assertEquals(CredentialReplaceResult.Stale, store.replace(1, reset(candidate())))
            api.beforeUpdate = { api.items.clear() }
            assertEquals(CredentialReplaceResult.Missing, store.replace(1, reset(candidate())))
            assertEquals(CredentialReplaceResult.Missing, store.replace(1, reset(candidate())))
            assertFalse(api.calls.any { it.operation == "add" })
        }

    @Test
    fun silentWriteAndFullFieldReadBackMismatchAreNotStored() =
        runTest {
            val api = KeychainFake()
            val store = store(api)
            api.addStatus = errSecSuccess
            failure(store.createIfMissing(candidate()), InstallationPermanentFailure.READ_BACK_MISMATCH)
            api.addStatus = null
            api.afterAdd = { it.bytes = bytes(candidate(otherMaterial())) }
            failure(store.createIfMissing(candidate()), InstallationPermanentFailure.READ_BACK_MISMATCH)
            api.items.clear()
            api.items += item(candidate())
            api.updateStatus = errSecSuccess
            failure(store.replace(1, reset(candidate())), InstallationPermanentFailure.READ_BACK_MISMATCH)
            assertRecord(candidate(), store.read())
        }

    @Test
    fun readBackRejectsChangedProtectionAndAConcurrentMarker() =
        runTest {
            val api = KeychainFake()
            val store = store(api)
            api.afterAdd = { it.group = OTHER_GROUP }
            failure(store.createIfMissing(candidate()), InstallationPermanentFailure.STATE_CHANGED)
            assertEquals(OTHER_GROUP, api.items.single().group)
            assertFalse(api.calls.any { it.operation == "delete" })
            api.items.clear()
            api.afterAdd = { api.items += markerItem(marker(2)) }
            failure(store.createIfMissing(candidate()), InstallationPermanentFailure.MARKER_CONFLICT)
            assertEquals(2, api.items.size)
        }

    @Test
    fun broadReadsRefuseSyncedForeignGroupMisprotectedAndDuplicatePieces() =
        runTest {
            val variants =
                listOf<(FakeItem) -> Unit>(
                    { it.synchronizable = true },
                    { it.group = OTHER_GROUP },
                    { it.accessible = kSecAttrAccessibleAfterFirstUnlock },
                    { it.omitSynchronization = true },
                    { it.returnedAccount = MARKER },
                    { it.returnedService = "unexpected.service" },
                )
            for (change in variants) {
                val api = KeychainFake()
                api.items += item(candidate()).also(change)
                val store = store(api)
                failure(store.read(), InstallationPermanentFailure.STATE_CHANGED)
                failure(store.createIfMissing(candidate()), InstallationPermanentFailure.STATE_CHANGED)
                api.items += markerItem(marker(null))
                failure(store.finishMarkedCleanup(marker(null)), InstallationPermanentFailure.STATE_CHANGED)
                assertFalse(api.calls.any { it.operation != "read" })
            }
            val api = KeychainFake()
            api.items += item(candidate())
            api.items += item(candidate()).also { it.synchronizable = true }
            api.items += item(candidate()).also { it.group = OTHER_GROUP }
            failure(store(api).read(), InstallationPermanentFailure.STATE_CHANGED)
            assertEquals(2, api.largestReturnedCount)
        }

    @Test
    fun nativePayloadTypeAndByteCeilingsAreEnforcedBeforeCodecAdmission() =
        runTest {
            val credentialBounds =
                listOf(
                    0 to InstallationPermanentFailure.CORRUPT,
                    2048 to InstallationPermanentFailure.CORRUPT,
                    2049 to InstallationPermanentFailure.TOO_LARGE,
                )
            for ((length, expected) in credentialBounds) {
                val api = KeychainFake().also { it.items += FakeItem(CREDENTIAL, ByteArray(length) { 32 }) }
                failure(store(api).read(), expected)
            }
            val markerBounds =
                listOf(512 to InstallationPermanentFailure.CORRUPT, 513 to InstallationPermanentFailure.TOO_LARGE)
            for ((length, expected) in markerBounds) {
                val api = KeychainFake().also { it.items += FakeItem(MARKER, ByteArray(length) { 32 }) }
                failure(store(api).readCleanupMarker(), expected)
            }
            val api = KeychainFake().also { it.items += item(candidate()).also { item -> item.omitData = true } }
            failure(store(api).read(), InstallationPermanentFailure.CORRUPT)
        }

    @Test
    fun markersAreCreateOnlyAndExactExistingMarkersResume() =
        runTest {
            val api = KeychainFake()
            val store = store(api)
            val expected = marker(2)
            assertEquals(CleanupMarkerCreateResult.Stored, store.createCleanupMarkerIfMissing(expected))
            assertEquals(CleanupMarkerCreateResult.AlreadyPresent, store.createCleanupMarkerIfMissing(expected))
            failure(store.createCleanupMarkerIfMissing(marker(3)), InstallationPermanentFailure.MARKER_CONFLICT)
            val read = assertIs<CleanupMarkerReadResult.Present>(store.readCleanupMarker())
            assertTrue(read.marker.sameAs(expected))
            api.items.single().bytes = byteArrayOf()
            failure(store.createCleanupMarkerIfMissing(expected), InstallationPermanentFailure.CORRUPT)
            assertEquals(1, api.calls.count { it.operation == "add" })
            assertFalse(api.calls.any { it.operation == "update" || it.operation == "delete" })
        }

    @Test
    fun markerDuplicateRaceAndReadBackMismatchNeverOverwrite() =
        runTest {
            val api = KeychainFake()
            val expected = marker(2)
            api.beforeAdd = { api.items += markerItem(expected) }
            assertEquals(CleanupMarkerCreateResult.AlreadyPresent, store(api).createCleanupMarkerIfMissing(expected))
            api.items.clear()
            api.beforeAdd = { api.items += markerItem(marker(3)) }
            failure(store(api).createCleanupMarkerIfMissing(expected), InstallationPermanentFailure.MARKER_CONFLICT)
            api.items.clear()
            api.beforeAdd = null
            api.addStatus = errSecSuccess
            failure(store(api).createCleanupMarkerIfMissing(expected), InstallationPermanentFailure.READ_BACK_MISMATCH)
            assertFalse(api.calls.any { it.operation == "update" || it.operation == "delete" })
        }

    @Test
    fun deletionRequiresExactDurableMarkerGenerationAndReason() =
        runTest {
            val api = KeychainFake().also { it.items += item(reset(candidate())) }
            val store = store(api)
            val expected = marker(2)
            assertEquals(CredentialDeleteResult.Stale, store.delete(2, expected))
            api.items += markerItem(expected)
            assertEquals(CredentialDeleteResult.Stale, store.delete(1, expected))
            assertEquals(CredentialDeleteResult.Stale, store.finishMarkedCleanup(marker(3)))
            val wrongReason = marker(2, CredentialCleanupReason.SERVER_TERMINAL_CONFIRMED)
            assertEquals(CredentialDeleteResult.Stale, store.finishMarkedCleanup(wrongReason))
            assertFalse(api.calls.any { it.operation == "delete" })
            assertEquals(CredentialDeleteResult.Deleted, store.delete(2, expected))
            assertEquals(CredentialReadResult.Missing, store.read())
            assertIs<CleanupMarkerReadResult.Present>(store.readCleanupMarker())
            assertEquals(CredentialDeleteResult.Missing, store.finishMarkedCleanup(expected))
            assertEquals(CleanupMarkerRemoveResult.Removed, store.removeCleanupMarker(expected))
            assertEquals(CleanupMarkerReadResult.Missing, store.readCleanupMarker())
        }

    @Test
    fun cleanupReasonMustMatchReadableStateAndNullMarkerNeverDeletesReadableRecord() =
        runTest {
            val cases =
                listOf(
                    candidate() to marker(null),
                    reset(candidate()) to marker(null),
                    deleting(candidate()) to marker(null),
                    candidate() to marker(1),
                    deleting(candidate()) to marker(2),
                    reset(candidate()) to marker(2, CredentialCleanupReason.SERVER_TERMINAL_CONFIRMED),
                )
            for ((record, marker) in cases) {
                val api =
                    KeychainFake().also {
                        it.items += item(record)
                        it.items += markerItem(marker)
                    }
                val store = store(api)
                assertEquals(CredentialDeleteResult.Stale, store.finishMarkedCleanup(marker))
                if (marker.expectedGeneration == null) {
                    failure(store.resetUnreadableAfterConfirmation(marker), InstallationPermanentFailure.STATE_CHANGED)
                }
                assertFalse(api.calls.any { it.operation == "delete" })
                assertEquals(2, api.items.size)
            }
            val deletionReasons =
                listOf(
                    CredentialCleanupReason.SERVER_TERMINAL_CONFIRMED,
                    CredentialCleanupReason.REMOTE_DELETE_ABANDON_CONFIRMED,
                )
            for (reason in deletionReasons) {
                val marker = marker(2, reason)
                val api =
                    KeychainFake().also {
                        it.items += item(deleting(candidate()))
                        it.items += markerItem(marker)
                    }
                assertEquals(CredentialDeleteResult.Deleted, store(api).finishMarkedCleanup(marker))
                assertEquals(MARKER, api.items.single().account)
            }
        }

    @Test
    fun positiveAndNullMarkersResumeOnlyVerifiedUnreadableFixedPayloads() =
        runTest {
            for (generation in listOf(2L, null)) {
                for (payload in listOf(byteArrayOf(), ByteArray(2049))) {
                    val marker = marker(generation)
                    val api =
                        KeychainFake().also {
                            it.items += FakeItem(CREDENTIAL, payload)
                            it.items += markerItem(marker)
                        }
                    val store = store(api)
                    if (generation == null) {
                        assertEquals(CredentialResetResult.Deleted, store.resetUnreadableAfterConfirmation(marker))
                    } else {
                        assertEquals(CredentialDeleteResult.Deleted, store.finishMarkedCleanup(marker))
                    }
                    assertEquals(MARKER, api.items.single().account)
                }
            }
            val api = KeychainFake().also { it.items += markerItem(marker(null)) }
            assertEquals(CredentialResetResult.Missing, store(api).resetUnreadableAfterConfirmation(marker(null)))
        }

    @Test
    fun nativeDecodeErrorUsesBoundedAttributeOnlyProofBeforeCleanup() =
        runTest {
            val marker = marker(2)
            val api =
                KeychainFake().also {
                    it.items += item(reset(candidate()))
                    it.items += markerItem(marker)
                }
            api.copyFailure = { account, includeData ->
                if (account == CREDENTIAL && includeData && api.items.any { it.account == CREDENTIAL }) {
                    errSecDecode
                } else {
                    null
                }
            }
            val store = store(api)
            failure(store.read(), InstallationPermanentFailure.CORRUPT)
            api.items.first().group = OTHER_GROUP
            failure(store.finishMarkedCleanup(marker), InstallationPermanentFailure.STATE_CHANGED)
            assertFalse(api.calls.any { it.operation == "delete" })
            api.items.first().group = GROUP
            api.items.first().accessible = kSecAttrAccessibleAfterFirstUnlock
            failure(store.finishMarkedCleanup(marker), InstallationPermanentFailure.STATE_CHANGED)
            api.items.first().accessible = kSecAttrAccessibleWhenUnlockedThisDeviceOnly
            api.items.first().synchronizable = true
            failure(store.finishMarkedCleanup(marker), InstallationPermanentFailure.STATE_CHANGED)
            api.items.first().synchronizable = false
            assertEquals(CredentialDeleteResult.Deleted, store.finishMarkedCleanup(marker))
            assertTrue(api.attributeOnlyReads >= 5)
            assertEquals(MARKER, api.items.single().account)
        }

    @Test
    fun temporaryAndUnsupportedCredentialFailuresCannotBecomeCleanupAuthority() =
        runTest {
            val marker = marker(null)
            val api =
                KeychainFake().also {
                    it.items += item(candidate())
                    it.items += markerItem(marker)
                }
            val store = store(api)
            api.copyFailure = { account, _ -> if (account == CREDENTIAL) errSecInteractionNotAllowed else null }
            unavailable(store.finishMarkedCleanup(marker), InstallationTemporaryFailure.LOCKED)
            api.copyFailure = null
            api.items.first().bytes = bytes(candidate(material("ANDROID")))
            failure(store.finishMarkedCleanup(marker), InstallationPermanentFailure.UNSUPPORTED)
            assertFalse(api.calls.any { it.operation == "delete" })
        }

    @Test
    fun failedOrSilentCredentialDeletionRetainsMarkerAndReconstructionResumes() =
        runTest {
            val marker = marker(2)
            val api =
                KeychainFake().also {
                    it.items += item(reset(candidate()))
                    it.items += markerItem(marker)
                }
            val store = store(api)
            api.deleteStatus = errSecIO
            unavailable(store.finishMarkedCleanup(marker), InstallationTemporaryFailure.IO_FAILURE)
            api.deleteStatus = errSecSuccess
            failure(store.finishMarkedCleanup(marker), InstallationPermanentFailure.READ_BACK_MISMATCH)
            assertEquals(2, api.items.size)
            api.deleteStatus = null
            assertEquals(CredentialDeleteResult.Deleted, store(api).finishMarkedCleanup(marker))
            assertEquals(MARKER, api.items.single().account)
        }

    @Test
    fun markerRemovalRequiresAbsentCredentialExactMarkerAndVerifiedDeletion() =
        runTest {
            val marker = marker(2)
            val api =
                KeychainFake().also {
                    it.items += item(reset(candidate()))
                    it.items += markerItem(marker)
                }
            val store = store(api)
            failure(store.removeCleanupMarker(marker), InstallationPermanentFailure.READ_BACK_MISMATCH)
            api.items.removeAt(0)
            assertEquals(CleanupMarkerRemoveResult.Stale, store.removeCleanupMarker(marker(3)))
            api.deleteStatus = errSecSuccess
            failure(store.removeCleanupMarker(marker), InstallationPermanentFailure.READ_BACK_MISMATCH)
            assertEquals(1, api.items.size)
            api.deleteStatus = null
            assertEquals(CleanupMarkerRemoveResult.Removed, store.removeCleanupMarker(marker))
            assertEquals(CleanupMarkerRemoveResult.Missing, store.removeCleanupMarker(marker))
        }

    @Test
    fun conflictingMarkerProtectionAndAttributesBlockDeletionRatherThanDisappear() =
        runTest {
            val marker = marker(2)
            val api =
                KeychainFake().also { it.items += markerItem(marker).also { entry -> entry.synchronizable = true } }
            val store = store(api)
            failure(store.readCleanupMarker(), InstallationPermanentFailure.STATE_CHANGED)
            failure(store.createCleanupMarkerIfMissing(marker), InstallationPermanentFailure.STATE_CHANGED)
            failure(store.finishMarkedCleanup(marker), InstallationPermanentFailure.STATE_CHANGED)
            failure(store.removeCleanupMarker(marker), InstallationPermanentFailure.STATE_CHANGED)
            assertFalse(api.calls.any { it.operation != "read" })
        }

    @Test
    fun cancellationAfterNativeCreationPropagatesAndLeavesTheWinnerForReconciliation() =
        runTest {
            val api = KeychainFake()
            api.afterAdd = { throw CancellationException("synthetic cancellation") }
            assertFailsWith<CancellationException> { store(api).createIfMissing(candidate()) }
            api.afterAdd = null
            assertRecord(candidate(), store(api).read())
            assertEquals(CredentialCreateResult.AlreadyPresent, store(api).createIfMissing(candidate(otherMaterial())))
            assertFalse(api.calls.any { it.operation == "delete" })
        }

    @Test
    fun cancellationAfterMarkerCommitAndCredentialDeletePreservesExactCleanupContinuation() =
        runTest {
            val marker = marker(2)
            val api = KeychainFake().also { it.items += item(reset(candidate())) }
            api.afterAdd = { throw CancellationException("synthetic cancellation") }
            assertFailsWith<CancellationException> { store(api).createCleanupMarkerIfMissing(marker) }
            api.afterAdd = null
            api.afterDelete = { throw CancellationException("synthetic cancellation") }
            assertFailsWith<CancellationException> { store(api).finishMarkedCleanup(marker) }
            assertEquals(MARKER, api.items.single().account)
            api.afterDelete = null
            val resumed = store(api)
            assertEquals(CredentialDeleteResult.Missing, resumed.finishMarkedCleanup(marker))
            assertEquals(CleanupMarkerRemoveResult.Removed, resumed.removeCleanupMarker(marker))
        }

    @Test
    fun cancelledJobsBeforeCallAndAfterNativeMutationCannotReturnSuccess() =
        runTest {
            val api = KeychainFake()
            val cancelled = Job().also { it.cancel() }
            assertFailsWith<CancellationException> {
                withContext(cancelled) { store(api).createIfMissing(candidate()) }
            }
            assertTrue(api.calls.isEmpty())
            val owner = Job()
            api.afterAdd = { owner.cancel() }
            assertFailsWith<CancellationException> {
                withContext(owner) { store(api).createIfMissing(candidate()) }
            }
            assertEquals("add", api.calls.last().operation)
            assertEquals(1, api.items.size)
            api.afterAdd = null
            assertRecord(candidate(), store(api).read())
        }

    @Test
    fun twoAdapterInstancesShareTheCreateAndGenerationSerializationBoundary() =
        runTest {
            val api = KeychainFake()
            val first = store(api)
            val second = store(api)
            val createOne = async(Dispatchers.Default) { first.createIfMissing(candidate()) }
            val createTwo = async(Dispatchers.Default) { second.createIfMissing(candidate(otherMaterial())) }
            assertEquals(
                setOf(CredentialCreateResult.Stored, CredentialCreateResult.AlreadyPresent),
                setOf(createOne.await(), createTwo.await()),
            )
            val winner = assertIs<CredentialReadResult.Present>(first.read()).record
            val replaceOne = async(Dispatchers.Default) { first.replace(1, reset(winner)) }
            val replaceTwo = async(Dispatchers.Default) { second.replace(1, deleting(winner)) }
            assertEquals(
                setOf(CredentialReplaceResult.Stored, CredentialReplaceResult.Stale),
                setOf(replaceOne.await(), replaceTwo.await()),
            )
            assertEquals(1, api.items.size)
            assertEquals(1, api.calls.count { it.operation == "add" })
            assertEquals(1, api.calls.count { it.operation == "update" })
        }
}

/**
 * NOT_RUN: ignored pending primary-authorized native qualification, supplier clearance and an independently
 * verified TEST signing group. Enabling this class requires a deliberate source change, not an absent-input
 * early return that ordinary test discovery could misreport as a pass.
 * No environment default and no production service. A failed native check may retain only its synthetic,
 * unique test namespace; no broad, unverified or cross-group teardown is attempted.
 */
@Ignore
class IosInstallationCredentialStoreNativeTest {
    @Test
    fun realKeychainIsolatedCreateDuplicateReplaceAndMarkerRecovery() =
        runTest {
            val group =
                assertNotNull(
                    NSProcessInfo.processInfo.environment["KIRA_W07_TEST_ACCESS_GROUP"] as? String,
                    "An explicit verified test signing access group is required",
                )
            assertTrue(group.isNotBlank(), "An explicit verified test signing access group is required")
            val service = "$PRODUCTION_SERVICE.test.${NSUUID().UUIDString.lowercase()}"
            val first = IosInstallationCredentialStore.isolatedForTest(group, service)
            // Bounded synthetic namespace only, so a failed qualification's retained items are identifiable.
            println("W07_KEYCHAIN_TEST_SERVICE=$service")
            assertEquals(CredentialReadResult.Missing, first.read())
            assertEquals(CleanupMarkerReadResult.Missing, first.readCleanupMarker())
            val record = candidate()
            assertEquals(CredentialCreateResult.Stored, first.createIfMissing(record))
            assertEquals(CredentialCreateResult.AlreadyPresent, first.createIfMissing(candidate(otherMaterial())))
            assertRecord(record, first.read())
            val next = reset(record)
            assertEquals(CredentialReplaceResult.Stored, first.replace(1, next))
            assertRecord(next, first.read())
            val marker = marker(next.localGeneration)
            assertEquals(CleanupMarkerCreateResult.Stored, first.createCleanupMarkerIfMissing(marker))
            val resumed = IosInstallationCredentialStore.isolatedForTest(group, service)
            assertEquals(CredentialDeleteResult.Deleted, resumed.finishMarkedCleanup(marker))
            assertEquals(CredentialReadResult.Missing, resumed.read())
            assertIs<CleanupMarkerReadResult.Present>(resumed.readCleanupMarker())
            assertEquals(CleanupMarkerRemoveResult.Removed, resumed.removeCleanupMarker(marker))
            assertEquals(CleanupMarkerReadResult.Missing, resumed.readCleanupMarker())
            assertEquals(CredentialReadResult.Missing, resumed.read())
        }
}

private class KeychainFake : IosInstallationSecItemApi {
    val items = mutableListOf<FakeItem>()
    val calls = mutableListOf<ApiCall>()
    var copyFailure: ((String, Boolean) -> Int?)? = null
    var addStatus: Int? = null
    var updateStatus: Int? = null
    var deleteStatus: Int? = null
    var beforeAdd: (() -> Unit)? = null
    var afterAdd: ((FakeItem) -> Unit)? = null
    var beforeUpdate: (() -> Unit)? = null
    var afterDelete: (() -> Unit)? = null
    var largestReturnedCount = 0
    var attributeOnlyReads = 0

    override fun copyMatching(
        query: CFDictionaryRef,
        result: CPointer<CFTypeRefVar>,
    ): Int {
        val account = checkedQuery(query, "read")
        val includeData = flag(query, kSecReturnData)
        if (!includeData) attributeOnlyReads++
        copyFailure?.invoke(account, includeData)?.let { return it }
        val matching = matches(query).take(2)
        return if (matching.isEmpty()) {
            errSecItemNotFound
        } else {
            largestReturnedCount = maxOf(largestReturnedCount, matching.size)
            val array = assertNotNull(CFArrayCreateMutable(null, 2, kCFTypeArrayCallBacks.ptr))
            for (item in matching) {
                val attributes = item.attributes(includeData)
                try {
                    CFArrayAppendValue(array, attributes)
                } finally {
                    CFRelease(attributes)
                }
            }
            result.pointed.value = array
            errSecSuccess
        }
    }

    override fun add(attributes: CFDictionaryRef): Int {
        val account = checkedQuery(attributes, "add")
        beforeAdd?.invoke()
        addStatus?.let { return it }
        val duplicate =
            items.any { it.account == account && it.service == SERVICE && it.group == GROUP && !it.synchronizable }
        return if (duplicate) {
            errSecDuplicateItem
        } else {
            val entry = FakeItem(account, dataBytes(attributes))
            items += entry
            afterAdd?.invoke(entry)
            errSecSuccess
        }
    }

    override fun update(
        query: CFDictionaryRef,
        attributes: CFDictionaryRef,
    ): Int {
        checkedQuery(query, "update")
        beforeUpdate?.invoke()
        updateStatus?.let { return it }
        val selected = matches(query)
        return if (selected.isEmpty()) {
            errSecItemNotFound
        } else {
            assertEquals(1, selected.size)
            selected.single().bytes = dataBytes(attributes)
            errSecSuccess
        }
    }

    override fun delete(query: CFDictionaryRef): Int {
        checkedQuery(query, "delete")
        deleteStatus?.let { return it }
        val selected = matches(query)
        return if (selected.isEmpty()) {
            errSecItemNotFound
        } else {
            assertEquals(1, selected.size)
            items.removeAll(selected.toSet())
            afterDelete?.invoke()
            errSecSuccess
        }
    }

    private fun checkedQuery(
        query: CFDictionaryRef,
        operation: String,
    ): String {
        assertTrue(keychainCfStringEquals(CFDictionaryGetValue(query, kSecClass), kSecClassGenericPassword))
        assertTrue(text(query, kSecAttrService) == SERVICE, "Only the isolated test service is permitted")
        val account = assertNotNull(text(query, kSecAttrAccount))
        assertTrue(account == CREDENTIAL || account == MARKER)
        calls += ApiCall(operation, account)
        val rawContext = assertNotNull(CFDictionaryGetValue(query, kSecUseAuthenticationContext))
        val context = CFBridgingRelease(CFRetain(rawContext)) as? LAContext
        assertTrue(context?.interactionNotAllowed == true, "Native authentication UI must be disabled, never skipped")
        if (operation == "read") {
            assertTrue(
                keychainCfStringEquals(CFDictionaryGetValue(query, kSecAttrSynchronizable), kSecAttrSynchronizableAny),
            )
            assertTrue(CFDictionaryGetValue(query, kSecAttrAccessGroup) == null)
            assertTrue(CFDictionaryGetValue(query, kSecAttrAccessible) == null)
            assertTrue(flag(query, kSecReturnAttributes))
            assertEquals(2, number(query, kSecMatchLimit))
        } else {
            assertTrue(keychainCfBooleanEquals(CFDictionaryGetValue(query, kSecAttrSynchronizable), kCFBooleanFalse))
            assertTrue(
                keychainCfStringEquals(
                    CFDictionaryGetValue(query, kSecAttrAccessible),
                    kSecAttrAccessibleWhenUnlockedThisDeviceOnly,
                ),
            )
            if (operation == "add") {
                assertTrue(CFDictionaryGetValue(query, kSecAttrAccessGroup) == null)
            } else {
                assertTrue(text(query, kSecAttrAccessGroup) == GROUP, "Mutation must target the verified group")
            }
        }
        return account
    }

    private fun matches(query: CFDictionaryRef): List<FakeItem> {
        val service = text(query, kSecAttrService)
        val account = text(query, kSecAttrAccount)
        val group = text(query, kSecAttrAccessGroup)
        val anySync =
            keychainCfStringEquals(CFDictionaryGetValue(query, kSecAttrSynchronizable), kSecAttrSynchronizableAny)
        val accessible = CFDictionaryGetValue(query, kSecAttrAccessible)
        return items.filter {
            it.service == service &&
                it.account == account &&
                (group == null || it.group == group) &&
                (anySync || !it.synchronizable) &&
                (accessible == null || keychainCfStringEquals(accessible, it.accessible))
        }
    }
}

private data class ApiCall(
    val operation: String,
    val account: String,
)

private class FakeItem(
    val account: String,
    var bytes: ByteArray,
) {
    var service = SERVICE
    var group = GROUP
    var synchronizable = false
    var accessible = kSecAttrAccessibleWhenUnlockedThisDeviceOnly
    var omitSynchronization = false
    var omitData = false
    var returnedAccount: String? = null
    var returnedService: String? = null

    fun attributes(includeData: Boolean): CFMutableDictionaryRef {
        val dictionary =
            assertNotNull(
                CFDictionaryCreateMutable(
                    null,
                    0,
                    kCFTypeDictionaryKeyCallBacks.ptr,
                    kCFTypeDictionaryValueCallBacks.ptr,
                ),
            )
        testString(dictionary, kSecAttrService, returnedService ?: service)
        testString(dictionary, kSecAttrAccount, returnedAccount ?: account)
        testString(dictionary, kSecAttrAccessGroup, group)
        CFDictionarySetValue(dictionary, kSecClass, kSecClassGenericPassword)
        CFDictionarySetValue(dictionary, kSecAttrAccessible, accessible)
        if (!omitSynchronization) {
            CFDictionarySetValue(
                dictionary,
                kSecAttrSynchronizable,
                if (synchronizable) kCFBooleanTrue else kCFBooleanFalse,
            )
        }
        if (includeData && !omitData) {
            val data =
                if (bytes.isEmpty()) {
                    CFDataCreate(null, null, 0)
                } else {
                    bytes.usePinned { CFDataCreate(null, it.addressOf(0).reinterpret(), bytes.size.toLong()) }
                }
            assertNotNull(data)
            try {
                CFDictionarySetValue(dictionary, kSecValueData, data)
            } finally {
                CFRelease(data)
            }
        }
        return dictionary
    }

    override fun toString(): String = "FakeItem(redacted)"
}

private fun store(
    api: KeychainFake,
    group: String = GROUP,
): IosInstallationCredentialStore = IosInstallationCredentialStore.isolatedForTest(group, SERVICE, api)

private fun material(
    platform: String = "IOS",
    scope: String = SCOPE,
): InstallationCredentialMaterial = valid(InstallationCredentialMaterial.checked(ID, "A".repeat(43), platform, scope))

private fun otherMaterial(): InstallationCredentialMaterial =
    valid(InstallationCredentialMaterial.checked(OTHER_ID, "B".repeat(42) + "A", "IOS", SCOPE))

private fun candidate(material: InstallationCredentialMaterial = material()): InstallationCredentialRecord =
    InstallationCredentialRecord.candidate(material)

private fun reset(record: InstallationCredentialRecord): InstallationCredentialRecord = valid(record.beginLocalReset())

private fun deleting(record: InstallationCredentialRecord): InstallationCredentialRecord =
    valid(
        record.beginDeletion(DELETION_KEY),
    )

private fun record(
    version: Long = 1,
    generation: Long = 1,
    state: InstallationCredentialState = InstallationCredentialState.ACTIVE,
): InstallationCredentialRecord =
    valid(
        InstallationCredentialRecord.checked(1, material(), version, generation, state, null),
    )

private fun marker(
    generation: Long?,
    reason: CredentialCleanupReason =
        if (generation == null) {
            CredentialCleanupReason.UNREADABLE_RESET_CONFIRMED
        } else {
            CredentialCleanupReason.USER_RESET_CONFIRMED
        },
): CredentialCleanupMarker = valid(CredentialCleanupMarker.checked(1, generation, reason))

private fun item(record: InstallationCredentialRecord): FakeItem = FakeItem(CREDENTIAL, bytes(record))

private fun markerItem(marker: CredentialCleanupMarker): FakeItem {
    val encoded =
        assertIs<InstallationCodecResult.Value<InstallationEncodedPayload>>(CredentialCleanupMarkerCodec.encode(marker))
    return FakeItem(MARKER, encoded.value.copyBytes())
}

private fun bytes(record: InstallationCredentialRecord): ByteArray =
    assertIs<InstallationCodecResult.Value<InstallationEncodedPayload>>(InstallationCredentialCodec.encode(record))
        .value
        .copyBytes()

private fun <T> valid(value: InstallationValueResult<T>): T = assertIs<InstallationValueResult.Valid<T>>(value).value

private fun assertRecord(
    expected: InstallationCredentialRecord,
    actual: CredentialReadResult,
) {
    assertTrue(
        assertIs<CredentialReadResult.Present>(actual).record.sameAs(expected),
        "Complete credential read-back mismatch",
    )
}

private fun failure(
    actual: Any,
    expected: InstallationPermanentFailure,
) {
    assertEquals(expected, assertIs<InstallationStorageFailure.PermanentFailure>(actual).reason)
}

private fun unavailable(
    actual: Any,
    expected: InstallationTemporaryFailure,
) {
    assertEquals(expected, assertIs<InstallationStorageFailure.TemporarilyUnavailable>(actual).reason)
}

private fun testString(
    dictionary: CFMutableDictionaryRef,
    key: CFStringRef?,
    value: String,
) {
    val string = assertNotNull(CFStringCreateWithCString(null, value, kCFStringEncodingUTF8))
    try {
        CFDictionarySetValue(dictionary, key, string)
    } finally {
        CFRelease(string)
    }
}

private fun text(
    dictionary: CFDictionaryRef,
    key: CFStringRef?,
): String? {
    val value = CFDictionaryGetValue(dictionary, key) ?: return null
    val string: CFStringRef = value.reinterpret()
    val length = CFStringGetLength(string)
    assertTrue(length in 0L..1024L)
    val bytes = ByteArray(length.toInt() * 4 + 1)
    val success =
        bytes.usePinned { CFStringGetCString(string, it.addressOf(0), bytes.size.toLong(), kCFStringEncodingUTF8) }
    assertTrue(success)
    return bytes.takeWhile { it != 0.toByte() }.toByteArray().decodeToString()
}

private fun flag(
    dictionary: CFDictionaryRef,
    key: CFStringRef?,
): Boolean {
    val value = CFDictionaryGetValue(dictionary, key) ?: return false
    return keychainCfBooleanEquals(value, kCFBooleanTrue)
}

private fun number(
    dictionary: CFDictionaryRef,
    key: CFStringRef?,
): Int =
    memScoped {
        val value = assertNotNull(CFDictionaryGetValue(dictionary, key))
        val number = alloc<IntVar>()
        assertTrue(CFNumberGetValue(value.reinterpret(), kCFNumberIntType, number.ptr))
        number.value
    }

private fun dataBytes(dictionary: CFDictionaryRef): ByteArray {
    val value = assertNotNull(CFDictionaryGetValue(dictionary, kSecValueData))
    assertEquals(CFDataGetTypeID(), CFGetTypeID(value))
    val data: CFDataRef = value.reinterpret()
    val length = CFDataGetLength(data)
    assertTrue(length in 1L..2048L)
    return assertNotNull(CFDataGetBytePtr(data)).reinterpret<ByteVar>().readBytes(length.toInt())
}

private const val PRODUCTION_SERVICE = "me.manga.kira.complaint.installation.v1"
private const val SERVICE = "$PRODUCTION_SERVICE.test.10000000-0000-4000-8000-000000000001"
private const val GROUP = "TESTPREFIX.me.manga.kira"
private const val OTHER_GROUP = "OTHERPREFIX.me.manga.kira"
private const val CREDENTIAL = "credential"
private const val MARKER = "cleanup-marker"
private const val ID = "10000000-0000-4000-8000-000000000002"
private const val OTHER_ID = "10000000-0000-4000-8000-000000000003"
private const val SCOPE = "10000000-0000-4000-8000-000000000004"
private const val OTHER_SCOPE = "10000000-0000-4000-8000-000000000005"
private const val DELETION_KEY = "10000000-0000-4000-8000-000000000006"
