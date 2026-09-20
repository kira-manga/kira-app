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
import platform.CoreFoundation.CFDictionaryGetCount
import platform.CoreFoundation.CFDictionaryGetValue
import platform.CoreFoundation.CFDictionaryRef
import platform.CoreFoundation.CFDictionaryRemoveValue
import platform.CoreFoundation.CFDictionarySetValue
import platform.CoreFoundation.CFGetTypeID
import platform.CoreFoundation.CFMutableDictionaryRef
import platform.CoreFoundation.CFNumberGetTypeID
import platform.CoreFoundation.CFNumberGetValue
import platform.CoreFoundation.CFRelease
import platform.CoreFoundation.CFRetain
import platform.CoreFoundation.CFStringCreateWithBytes
import platform.CoreFoundation.CFStringGetCharacterAtIndex
import platform.CoreFoundation.CFStringGetLength
import platform.CoreFoundation.CFStringGetTypeID
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
import platform.Security.kSecClassInternetPassword
import platform.Security.kSecMatchLimit
import platform.Security.kSecReturnAttributes
import platform.Security.kSecReturnData
import platform.Security.kSecUseAuthenticationContext
import platform.Security.kSecValueData
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import me.manga.kira.platform.storage.InstallationPermanentFailure as Permanent
import me.manga.kira.platform.storage.InstallationStorageFailure.PermanentFailure as Refused
import me.manga.kira.platform.storage.InstallationStorageFailure.TemporarilyUnavailable as Unavailable
import me.manga.kira.platform.storage.InstallationTemporaryFailure as Temporary

/** Actual adapter over four CF primitive doubles; no real Keychain or signing/device qualification. */
class IosPendingComplaintActionStoreTest {
    @Test
    fun namespaceAndGroupInputsFailClosedWithoutGuessingSigningProvenance() =
        runTest {
            for (group in listOf("", " ", "*.app", "group..app", "a\u0000b", "a".repeat(256))) {
                val api = PendingSecItemFake()
                val store = pendingStore(api, group)
                pendingFailure(store.read(), Permanent.UNSUPPORTED)
                pendingFailure(store.createIfMissing(pendingSlot()), Permanent.UNSUPPORTED)
                pendingFailure(store.clearForConfirmedRecovery(), Permanent.UNSUPPORTED)
                assertTrue(api.calls.isEmpty())
            }
            for (service in listOf(PENDING_PRODUCTION_SERVICE, "other.test.${pendingId()}", "$PENDING_SERVICE.extra")) {
                assertFailsWith<IllegalArgumentException> {
                    IosPendingComplaintActionStore.isolatedForTest(PENDING_GROUP, service, PendingSecItemFake())
                }
            }
            val api = PendingSecItemFake()
            pendingFailure(
                pendingStore(api, PENDING_OTHER_GROUP).createIfMissing(pendingSlot()),
                Permanent.STATE_CHANGED,
            )
            assertEquals(PENDING_GROUP, api.items.single().group)
            assertEquals(listOf("add"), api.calls.filter { it.operation != "read" }.map { it.operation })
        }

    @Test
    fun emptyPayloadAndExactMaximumInventoryKeepDefensiveOwnershipAndNumericLimits() =
        runTest {
            val api = PendingSecItemFake()
            val store = pendingStore(api)
            assertInventory(store.read())
            val empty = pendingSlot(bytes = ByteArray(0))
            assertEquals(PendingCreateResult.Stored, store.createIfMissing(empty))
            assertInventory(store.read(), empty)
            val source = ByteArray(PendingComplaintSlot.MAX_BYTES) { 7 }
            val full = pendingSlot(2, source)
            source.fill(9)
            assertEquals(PendingCreateResult.Stored, store.createIfMissing(full))
            val detached =
                assertIs<PendingReadResult.Verified>(store.read())
                    .snapshot
                    .entries()
                    .last()
                    .bytes()
            detached.fill(0)
            assertInventory(store.read(), empty, full)
            api.items.clear()
            val maximum = (1..16).map { pendingSlot(it, ByteArray(2048) { it.toByte() }) }
            api.items += maximum.map(::pendingItem)
            val snapshot = assertIs<PendingReadResult.Verified>(store.read()).snapshot
            assertEquals(16, snapshot.size)
            assertEquals(32768, snapshot.logicalBytes)
            assertTrue(snapshot.entries().zip(maximum).all { (actual, expected) -> actual.sameAs(expected) })
            assertTrue(api.calls.filter { it.operation == "read" }.all { it.limit == 17 && it.includeData })
        }

    @Test
    fun oversizedItemsInventoryAndCapacityAreRefusedWithoutEviction() =
        runTest {
            val oversized =
                PendingSecItemFake().also {
                    it.items += PendingFakeItem("slot.${pendingId()}", ByteArray(2049))
                }
            pendingFailure(pendingStore(oversized).read(), Permanent.TOO_LARGE)
            pendingFailure(pendingStore(oversized).createIfMissing(pendingSlot(2)), Permanent.TOO_LARGE)
            assertFalse(oversized.calls.any { it.operation != "read" })
            for (count in listOf(16, 17)) {
                val api =
                    PendingSecItemFake().also { fake ->
                        fake.items += (1..count).map { pendingItem(pendingSlot(it, ByteArray(2048))) }
                    }
                val store = pendingStore(api)
                pendingFailure(store.createIfMissing(pendingSlot(18)), Permanent.TOO_LARGE)
                if (count == 16) {
                    assertEquals(PendingCreateResult.AlreadyPresent, store.createIfMissing(pendingSlot()))
                } else {
                    pendingFailure(store.read(), Permanent.TOO_LARGE)
                    pendingFailure(store.delete(pendingSlot(1, ByteArray(2048))), Permanent.TOO_LARGE)
                }
                assertEquals(count, api.items.size)
                assertFalse(api.calls.any { it.operation != "read" })
                assertTrue(api.largestReturnedCount <= 17)
            }
        }

    @Test
    fun nativeStatusesAndMalformedSuccessResultsNeverBecomeEmptyInventory() =
        runTest {
            val failures =
                listOf(
                    errSecInteractionNotAllowed to Unavailable(Temporary.LOCKED),
                    errSecInteractionRequired to Unavailable(Temporary.LOCKED),
                    errSecIO to Unavailable(Temporary.IO_FAILURE),
                    errSecNotAvailable to Unavailable(Temporary.IO_FAILURE),
                    errSecDecode to Refused(Permanent.CORRUPT),
                    errSecUnimplemented to Refused(Permanent.UNSUPPORTED),
                    errSecMissingEntitlement to Refused(Permanent.UNSUPPORTED),
                    errSecParam to Refused(Permanent.UNSUPPORTED),
                    errSecAuthFailed to Unavailable(Temporary.UNCERTAIN),
                )
            for ((status, expected) in failures) {
                val api = PendingSecItemFake().also { it.statuses["read"] = status }
                val store = pendingStore(api)
                assertEquals(expected, store.read())
                assertEquals(expected, store.createIfMissing(pendingSlot()))
                assertEquals(expected, store.clearForConfirmedRecovery())
                assertFalse(api.calls.any { it.operation != "read" })
            }
            for (shape in 0..3) {
                val api = PendingSecItemFake()
                api.copyOverride = { _, result ->
                    result.pointed.value =
                        when (shape) {
                            0 -> null
                            1 -> assertNotNull(CFArrayCreateMutable(null, 0, kCFTypeArrayCallBacks.ptr))
                            2 -> CFRetain(kCFBooleanTrue)
                            else ->
                                assertNotNull(CFArrayCreateMutable(null, 1, kCFTypeArrayCallBacks.ptr)).also {
                                    CFArrayAppendValue(it, kCFBooleanTrue)
                                }
                        }
                    errSecSuccess
                }
                pendingFailure(pendingStore(api).read(), Permanent.STATE_CHANGED)
                pendingFailure(pendingStore(api).clearForConfirmedRecovery(), Permanent.STATE_CHANGED)
                assertFalse(api.calls.any { it.operation != "read" })
            }
        }

    @Test
    fun everyReturnedAttributeAndPayloadIsValidatedBeforeOrdinaryMutation() =
        runTest {
            val normalOnly =
                listOf<(PendingFakeItem) -> Unit>(
                    { it.account = "slot.not-a-uuid" },
                    { it.account = "slot.A0000000-0000-4000-8000-000000000001" },
                    { it.account = "slot.10000000-0000-5000-8000-000000000001" },
                    { it.account = "slot.${pendingId()}\u0000" },
                )
            for (damage in PendingAttributeDefects.fixedAndAccount() + normalOnly) {
                val api = PendingSecItemFake()
                api.items += pendingItem(pendingSlot())
                api.items += pendingItem(pendingSlot(2)).also(damage)
                val store = pendingStore(api)
                pendingFailure(store.read(), Permanent.STATE_CHANGED)
                pendingFailure(store.createIfMissing(pendingSlot(3)), Permanent.STATE_CHANGED)
                pendingFailure(
                    store.replace(pendingSlot(), pendingSlot(bytes = byteArrayOf(4))),
                    Permanent.STATE_CHANGED,
                )
                pendingFailure(store.delete(pendingSlot()), Permanent.STATE_CHANGED)
                assertFalse(api.calls.any { it.operation != "read" })
            }
            for (wrongType in listOf(false, true)) {
                val api = PendingSecItemFake()
                api.items +=
                    pendingItem(pendingSlot()).also { item ->
                        item.editAttributes = {
                            if (wrongType) {
                                CFDictionarySetValue(it, kSecValueData, kCFBooleanFalse)
                            } else {
                                CFDictionaryRemoveValue(it, kSecValueData)
                            }
                        }
                    }
                pendingFailure(pendingStore(api).read(), Permanent.CORRUPT)
                pendingFailure(pendingStore(api).createIfMissing(pendingSlot(2)), Permanent.CORRUPT)
                assertFalse(api.calls.any { it.operation != "read" })
            }
            val duplicate = PendingSecItemFake().also { it.items += List(2) { pendingItem(pendingSlot()) } }
            pendingFailure(pendingStore(duplicate).read(), Permanent.STATE_CHANGED)
        }

    @Test
    fun creationReadsTheDuplicateWinnerAndChecksTheWholeInventory() =
        runTest {
            val unrelated = pendingSlot(2)
            val winner = pendingSlot(bytes = byteArrayOf(9))
            val api = PendingSecItemFake().also { it.items += pendingItem(unrelated) }
            api.beforeCall = { if (it.operation == "add") api.items += pendingItem(winner) }
            assertEquals(PendingCreateResult.AlreadyPresent, pendingStore(api).createIfMissing(pendingSlot()))
            assertInventory(pendingStore(api).read(), unrelated, winner)
            assertFalse(api.calls.any { it.operation == "update" || it.operation == "delete" })
            api.beforeCall = null
            val adds = api.calls.count { it.operation == "add" }
            assertEquals(PendingCreateResult.AlreadyPresent, pendingStore(api).createIfMissing(pendingSlot()))
            assertEquals(adds, api.calls.count { it.operation == "add" })
            for (status in listOf(errSecSuccess, errSecDuplicateItem)) {
                val missing = PendingSecItemFake().also { it.statuses["add"] = status }
                pendingFailure(pendingStore(missing).createIfMissing(pendingSlot()), Permanent.READ_BACK_MISMATCH)
            }
            for (damage in 0..2) {
                val changed = PendingSecItemFake().also { it.items += pendingItem(unrelated) }
                changed.afterMutation = {
                    when (damage) {
                        0 -> changed.items.last().bytes = byteArrayOf(8)
                        1 -> changed.items.removeAt(0)
                        else -> changed.items.first().bytes = byteArrayOf(8)
                    }
                }
                pendingFailure(pendingStore(changed).createIfMissing(pendingSlot()), Permanent.READ_BACK_MISMATCH)
            }
        }

    @Test
    fun replacementAndDeletionCompareSameIdentityAndExactOpaqueBytes() =
        runTest {
            val original = pendingSlot()
            val replacement = pendingSlot(bytes = byteArrayOf(4, 5))
            val unrelated = pendingSlot(2)
            val api = PendingSecItemFake().also { it.items += listOf(pendingItem(original), pendingItem(unrelated)) }
            val store = pendingStore(api)
            assertEquals(PendingReplaceResult.Missing, store.replace(pendingSlot(3), pendingSlot(3, byteArrayOf(4))))
            pendingFailure(store.replace(original, pendingSlot(3)), Permanent.STATE_CHANGED)
            assertEquals(PendingReplaceResult.Stale, store.replace(replacement, original))
            assertEquals(PendingDeleteResult.Stale, store.delete(replacement))
            assertFalse(api.calls.any { it.operation != "read" })
            assertEquals(PendingReplaceResult.Stored, store.replace(original, replacement))
            assertInventory(store.read(), replacement, unrelated)
            assertEquals(PendingDeleteResult.Stale, store.delete(original))
            assertEquals(PendingDeleteResult.Deleted, store.delete(replacement))
            assertEquals(PendingDeleteResult.Missing, store.delete(replacement))
            assertInventory(store.read(), unrelated)
            assertFalse(api.calls.any { it.operation == "add" })
            assertEquals(1, api.calls.count { it.operation == "update" })
            assertEquals(1, api.calls.count { it.operation == "delete" })
        }

    @Test
    fun missingAndSilentMutationResultsReconcileWithoutUpdateToAddFallback() =
        runTest {
            val expected = pendingSlot()
            val replacement = pendingSlot(bytes = byteArrayOf(7))
            for (operation in listOf("update", "delete")) {
                for (removed in listOf(false, true)) {
                    val api = PendingSecItemFake().also { it.items += pendingItem(expected) }
                    api.statuses[operation] = errSecItemNotFound
                    api.beforeCall = { if (it.operation == operation && removed) api.items.clear() }
                    val store = pendingStore(api)
                    if (operation == "update") {
                        assertEquals(
                            if (removed) PendingReplaceResult.Missing else PendingReplaceResult.Stale,
                            store.replace(expected, replacement),
                        )
                    } else {
                        assertEquals(
                            if (removed) PendingDeleteResult.Missing else PendingDeleteResult.Stale,
                            store.delete(expected),
                        )
                    }
                    assertFalse(api.calls.any { it.operation == "add" })
                }
                val silent =
                    PendingSecItemFake().also {
                        it.items += pendingItem(expected)
                        it.statuses[operation] = errSecSuccess
                    }
                val result =
                    if (operation == "update") {
                        pendingStore(silent).replace(expected, replacement)
                    } else {
                        pendingStore(silent).delete(expected)
                    }
                pendingFailure(result, Permanent.READ_BACK_MISMATCH)
                assertContentEquals(expected.bytes(), silent.items.single().bytes)
                val changed =
                    PendingSecItemFake().also {
                        it.items += listOf(pendingItem(expected), pendingItem(pendingSlot(2)))
                    }
                changed.afterMutation = {
                    changed.items.first { it.account == "slot.${pendingId(2)}" }.bytes = byteArrayOf(9)
                }
                val changedResult =
                    if (operation == "update") {
                        pendingStore(changed).replace(expected, replacement)
                    } else {
                        pendingStore(changed).delete(expected)
                    }
                pendingFailure(changedResult, Permanent.READ_BACK_MISMATCH)
            }
        }

    // Keep the six operation/timing cases together, each with exact retained-inventory assertions.
    @Suppress("CyclomaticComplexMethod")
    @Test
    fun nativeMutationAndPostCommitReadFailuresNeverReportSuccess() =
        runTest {
            for (operation in listOf("add", "update", "delete")) {
                for (afterCommit in listOf(false, true)) {
                    val api = PendingSecItemFake()
                    if (operation != "add") api.items += pendingItem(pendingSlot())
                    if (afterCommit) {
                        api.afterMutation = { api.statuses["read"] = errSecNotAvailable }
                    } else {
                        api.statuses[operation] = errSecIO
                    }
                    val store = pendingStore(api)
                    val result =
                        when (operation) {
                            "add" -> store.createIfMissing(pendingSlot())
                            "update" -> store.replace(pendingSlot(), pendingSlot(bytes = byteArrayOf(9)))
                            else -> store.delete(pendingSlot())
                        }
                    pendingUnavailable(result, Temporary.IO_FAILURE)
                    api.statuses.clear()
                    val expected =
                        when {
                            operation == "delete" && afterCommit -> emptyArray()
                            operation == "add" && !afterCommit -> emptyArray()
                            operation == "update" && afterCommit -> arrayOf(pendingSlot(bytes = byteArrayOf(9)))
                            else -> arrayOf(pendingSlot())
                        }
                    assertInventory(pendingStore(api).read(), *expected)
                }
            }
        }
}

class IosPendingComplaintActionRecoveryTest {
    @Test
    fun malformedSuffixesEmbeddedNulAndCorruptPayloadsUseOnlyBoundedAttributes() =
        runTest {
            val accounts =
                listOf(
                    "slot.",
                    "slot.bad",
                    "slot.bad\u0000tail",
                    "slot." + "x".repeat(123),
                    "slot.\uD83D\uDE00",
                )
            val api = PendingSecItemFake()
            api.items += accounts.map { PendingFakeItem(it, ByteArray(4096)) }
            api.items[0].editAttributes = { CFDictionaryRemoveValue(it, kSecValueData) }
            api.items[1].editAttributes = { CFDictionarySetValue(it, kSecValueData, kCFBooleanFalse) }
            assertEquals(PendingClearResult.Cleared, pendingStore(api).clearForConfirmedRecovery())
            assertTrue(api.items.isEmpty())
            assertEquals(
                accounts.toSet(),
                api.calls
                    .filter { it.operation == "delete" }
                    .map { it.account }
                    .toSet(),
            )
            assertEquals(0, api.returnedDataBytes)
            assertTrue(api.calls.filter { it.operation == "read" }.all { !it.includeData })
            assertTrue(api.calls.filter { it.operation == "read" && it.account != null }.all { it.limit == 2 })
            assertEquals(17, api.calls.last().limit)
        }

    @Test
    fun invalidRecoveryAttributesAndDuplicateAccountsRefuseTheWholeBatchBeforeDeletion() =
        runTest {
            for (damage in PendingAttributeDefects.fixedAndAccount()) {
                val api = PendingSecItemFake()
                api.items += pendingItem(pendingSlot())
                api.items += pendingItem(pendingSlot(2)).also(damage)
                pendingFailure(pendingStore(api).clearForConfirmedRecovery(), Permanent.STATE_CHANGED)
                assertEquals(2, api.items.size)
                assertFalse(api.calls.any { it.operation != "read" })
                assertEquals(0, api.returnedDataBytes)
            }
            val duplicate =
                PendingSecItemFake().also {
                    it.items += List(2) { PendingFakeItem("slot.bad", ByteArray(0)) }
                }
            pendingFailure(pendingStore(duplicate).clearForConfirmedRecovery(), Permanent.STATE_CHANGED)
            assertFalse(duplicate.calls.any { it.operation != "read" })
        }

    @Test
    fun perAccountRecheckRejectsLateConflictsAndNeverTruncatesRawAccounts() =
        runTest {
            for (foreign in listOf(false, true)) {
                val api = PendingSecItemFake().also { it.items += PendingFakeItem("slot.bad\u0000tail", ByteArray(0)) }
                api.beforeCall = { call ->
                    if (call.operation == "read" && call.account != null && api.items.size == 1) {
                        api.items +=
                            PendingFakeItem(assertNotNull(call.account), ByteArray(0)).also {
                                if (foreign) it.group = PENDING_OTHER_GROUP
                            }
                    }
                }
                pendingFailure(pendingStore(api).clearForConfirmedRecovery(), Permanent.STATE_CHANGED)
                assertFalse(api.calls.any { it.operation == "delete" })
            }
            val changed = PendingSecItemFake().also { it.items += PendingFakeItem("slot.bad", ByteArray(0)) }
            changed.beforeCall = { call ->
                if (call.operation == "read" && call.account != null) {
                    changed.items.single().editAttributes = {
                        PendingTestCf.putString(it, kSecAttrAccount, "slot.other")
                    }
                }
            }
            pendingFailure(pendingStore(changed).clearForConfirmedRecovery(), Permanent.STATE_CHANGED)
            assertFalse(changed.calls.any { it.operation == "delete" })
            val exact =
                PendingSecItemFake().also {
                    it.items +=
                        listOf(
                            PendingFakeItem("slot.bad", ByteArray(0)),
                            PendingFakeItem("slot.bad\u0000tail", ByteArray(0)),
                        )
                }
            assertEquals(PendingClearResult.Cleared, pendingStore(exact).clearForConfirmedRecovery())
            assertEquals(
                listOf("slot.bad", "slot.bad\u0000tail"),
                exact.calls.filter { it.operation == "delete" }.map { it.account },
            )
        }

    @Test
    fun eachRecoveryCallDeletesAtMostSeventeenAndReportsTheRemainingInventory() =
        runTest {
            for (count in listOf(18, 34)) {
                val api =
                    PendingSecItemFake().also { fake ->
                        fake.items += (1..count).map { pendingItem(pendingSlot(it)) }
                    }
                val result = pendingStore(api).clearForConfirmedRecovery()
                if (count == 18) {
                    pendingUnavailable(result, Temporary.UNCERTAIN)
                } else {
                    pendingFailure(result, Permanent.TOO_LARGE)
                }
                assertEquals(17, api.calls.count { it.operation == "delete" })
                assertEquals(36, api.calls.count { it.operation == "read" })
                assertEquals(count - 17, api.items.size)
                assertTrue(api.largestReturnedCount <= 17)
                assertEquals(0, api.returnedDataBytes)
                assertEquals(PendingClearResult.Cleared, pendingStore(api).clearForConfirmedRecovery())
                assertEquals(count, api.calls.count { it.operation == "delete" })
                assertTrue(api.items.isEmpty())
            }
        }

    @Test
    fun partialDeleteFailuresRetainEvidenceAndCanResumeThroughANewAdapter() =
        runTest {
            val api = PendingSecItemFake().also { fake -> fake.items += (1..3).map { pendingItem(pendingSlot(it)) } }
            api.beforeCall = { call ->
                if (call.operation == "delete" && call.account == "slot.${pendingId(2)}") {
                    api.statuses["delete"] = errSecIO
                }
            }
            pendingUnavailable(pendingStore(api).clearForConfirmedRecovery(), Temporary.IO_FAILURE)
            assertEquals(listOf("slot.${pendingId(2)}", "slot.${pendingId(3)}"), api.items.map { it.account })
            api.beforeCall = null
            api.statuses.clear()
            assertEquals(PendingClearResult.Cleared, pendingStore(api).clearForConfirmedRecovery())
            for (status in listOf(errSecSuccess, errSecItemNotFound)) {
                val silent =
                    PendingSecItemFake().also {
                        it.items += pendingItem(pendingSlot())
                        it.statuses["delete"] = status
                    }
                pendingFailure(pendingStore(silent).clearForConfirmedRecovery(), Permanent.READ_BACK_MISMATCH)
                assertEquals(1, silent.items.size)
            }
            val removed = PendingSecItemFake().also { it.items += pendingItem(pendingSlot()) }
            removed.beforeCall = { if (it.operation == "delete") removed.items.clear() }
            assertEquals(PendingClearResult.Cleared, pendingStore(removed).clearForConfirmedRecovery())
        }

    @Test
    fun finalEnumerationMustProveEmptyRatherThanTrustSuccessfulDeletes() =
        runTest {
            val late = PendingSecItemFake().also { it.items += pendingItem(pendingSlot()) }
            late.afterMutation = { late.items += pendingItem(pendingSlot(2)) }
            pendingUnavailable(pendingStore(late).clearForConfirmedRecovery(), Temporary.UNCERTAIN)
            assertEquals(1, late.items.size)
            late.afterMutation = null
            assertEquals(PendingClearResult.Cleared, pendingStore(late).clearForConfirmedRecovery())
            val malformed = PendingSecItemFake().also { it.items += pendingItem(pendingSlot()) }
            malformed.copyOverride = { query, result ->
                if (PendingTestCf.text(query, kSecAttrAccount) == null && malformed.items.isEmpty()) {
                    result.pointed.value = assertNotNull(CFArrayCreateMutable(null, 0, kCFTypeArrayCallBacks.ptr))
                    errSecSuccess
                } else {
                    null
                }
            }
            pendingFailure(pendingStore(malformed).clearForConfirmedRecovery(), Permanent.STATE_CHANGED)
            assertTrue(malformed.items.isEmpty())
            val unavailable = PendingSecItemFake().also { it.items += pendingItem(pendingSlot()) }
            unavailable.afterMutation = { unavailable.statuses["read"] = errSecIO }
            pendingUnavailable(pendingStore(unavailable).clearForConfirmedRecovery(), Temporary.IO_FAILURE)
            assertTrue(unavailable.items.isEmpty())
        }

    @Test
    fun cancellationDuringRecoveryRetainsPartialProgressAndReleasesTheProcessLock() =
        runTest {
            val api = PendingSecItemFake().also { fake -> fake.items += (1..2).map { pendingItem(pendingSlot(it)) } }
            val cancelled = CancellationException("synthetic recovery cancellation")
            api.afterMutation = { throw cancelled }
            assertSame(
                cancelled,
                assertFailsWith<CancellationException> { pendingStore(api).clearForConfirmedRecovery() },
            )
            assertEquals(1, api.items.size)
            assertEquals("delete", api.calls.last().operation)
            api.afterMutation = null
            assertEquals(PendingClearResult.Cleared, pendingStore(api).clearForConfirmedRecovery())
            api.items += (1..2).map { pendingItem(pendingSlot(it)) }
            val owner = Job()
            api.afterMutation = { owner.cancel() }
            assertFailsWith<CancellationException> {
                withContext(owner) { pendingStore(api).clearForConfirmedRecovery() }
            }
            assertEquals(1, api.items.size)
            assertEquals("delete", api.calls.last().operation)
            api.afterMutation = null
            assertEquals(PendingClearResult.Cleared, pendingStore(api).clearForConfirmedRecovery())
        }
}

class IosPendingComplaintActionConcurrencyTest {
    @Test
    fun thrownAndActualJobCancellationCannotReportMutationSuccess() =
        runTest {
            val api = PendingSecItemFake()
            val cancelled = CancellationException("synthetic pending cancellation")
            api.beforeCall = { throw cancelled }
            assertSame(cancelled, assertFailsWith<CancellationException> { pendingStore(api).read() })
            api.beforeCall = null
            api.afterMutation = { throw cancelled }
            assertSame(
                cancelled,
                assertFailsWith<CancellationException> { pendingStore(api).createIfMissing(pendingSlot()) },
            )
            api.afterMutation = null
            assertInventory(pendingStore(api).read(), pendingSlot())
            val before = PendingSecItemFake()
            val cancelledJob = Job().also { it.cancel() }
            assertFailsWith<CancellationException> {
                withContext(cancelledJob) { pendingStore(before).createIfMissing(pendingSlot()) }
            }
            assertTrue(before.calls.isEmpty())
            val owner = Job()
            before.afterMutation = { owner.cancel() }
            assertFailsWith<CancellationException> {
                withContext(owner) { pendingStore(before).createIfMissing(pendingSlot()) }
            }
            assertEquals("add", before.calls.last().operation)
            assertEquals(1, before.items.size)
            before.afterMutation = null
            assertInventory(pendingStore(before).read(), pendingSlot())
        }

    @Test
    fun twoInstancesSerializeCreationAndExactByteCas() =
        runTest {
            val api = PendingSecItemFake()
            val first = pendingStore(api)
            val second = pendingStore(api)
            val createOne = async(Dispatchers.Default) { first.createIfMissing(pendingSlot()) }
            val createTwo = async(Dispatchers.Default) { second.createIfMissing(pendingSlot(bytes = byteArrayOf(9))) }
            assertEquals(
                setOf(PendingCreateResult.Stored, PendingCreateResult.AlreadyPresent),
                setOf(createOne.await(), createTwo.await()),
            )
            val winner = assertIs<PendingReadResult.Verified>(first.read()).snapshot.entries().single()
            val one = pendingSlot(bytes = byteArrayOf(7))
            val two = pendingSlot(bytes = byteArrayOf(8))
            val replaceOne = async(Dispatchers.Default) { first.replace(winner, one) }
            val replaceTwo = async(Dispatchers.Default) { second.replace(winner, two) }
            assertEquals(
                setOf(PendingReplaceResult.Stored, PendingReplaceResult.Stale),
                setOf(replaceOne.await(), replaceTwo.await()),
            )
            val current = assertIs<PendingReadResult.Verified>(first.read()).snapshot.entries().single()
            assertTrue(current.sameAs(one) || current.sameAs(two))
            assertEquals(1, api.items.size)
            assertEquals(1, api.calls.count { it.operation == "add" })
            assertEquals(1, api.calls.count { it.operation == "update" })
        }
}

/** Only primitive matching/mutation and fault hooks: no duplicate implementation of store policy. */
private class PendingSecItemFake : IosInstallationSecItemApi {
    val items = mutableListOf<PendingFakeItem>()
    val calls = mutableListOf<PendingFakeCall>()
    val statuses = mutableMapOf<String, Int>()
    var beforeCall: ((PendingFakeCall) -> Unit)? = null
    var afterMutation: ((PendingFakeCall) -> Unit)? = null
    var copyOverride: ((CFDictionaryRef, CPointer<CFTypeRefVar>) -> Int?)? = null
    var largestReturnedCount = 0
    var returnedDataBytes = 0

    // Retain native-result CF try/finally inside the exact empty/nonempty primitive branches.
    @Suppress("NestedBlockDepth")
    override fun copyMatching(
        query: CFDictionaryRef,
        result: CPointer<CFTypeRefVar>,
    ): Int {
        val call = checkedQuery(query, "read")
        beforeCall?.invoke(call)
        val overrideStatus = statuses["read"] ?: copyOverride?.invoke(query, result)
        if (overrideStatus != null) return overrideStatus
        val matching = matches(query).take(assertNotNull(call.limit))
        return if (matching.isEmpty()) {
            errSecItemNotFound
        } else {
            largestReturnedCount = maxOf(largestReturnedCount, matching.size)
            val array = assertNotNull(CFArrayCreateMutable(null, matching.size.toLong(), kCFTypeArrayCallBacks.ptr))
            for (item in matching) {
                val attributes = item.attributes(call.includeData)
                try {
                    if (call.includeData) returnedDataBytes += item.bytes.size
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
        val call = checkedQuery(attributes, "add")
        beforeCall?.invoke(call)
        statuses["add"]?.let { return it }
        val account = assertNotNull(call.account)
        val duplicate =
            items.any {
                it.service == PENDING_SERVICE && it.account == account && it.group == PENDING_GROUP && !it.sync
            }
        return if (duplicate) {
            errSecDuplicateItem
        } else {
            items += PendingFakeItem(account, PendingTestCf.dataBytes(attributes))
            afterMutation?.invoke(call)
            errSecSuccess
        }
    }

    override fun update(
        query: CFDictionaryRef,
        attributes: CFDictionaryRef,
    ): Int {
        val call = checkedQuery(query, "update")
        assertEquals(1L, CFDictionaryGetCount(attributes), "Only the opaque data field may change")
        beforeCall?.invoke(call)
        statuses["update"]?.let { return it }
        val selected = matches(query)
        return if (selected.isEmpty()) {
            errSecItemNotFound
        } else {
            assertEquals(1, selected.size)
            selected.single().bytes = PendingTestCf.dataBytes(attributes)
            afterMutation?.invoke(call)
            errSecSuccess
        }
    }

    override fun delete(query: CFDictionaryRef): Int {
        val call = checkedQuery(query, "delete")
        beforeCall?.invoke(call)
        statuses["delete"]?.let { return it }
        val selected = matches(query)
        return if (selected.isEmpty()) {
            errSecItemNotFound
        } else {
            assertEquals(1, selected.size, "Deletion must remain one exact verified account")
            items.remove(selected.single())
            afterMutation?.invoke(call)
            errSecSuccess
        }
    }

    private fun checkedQuery(
        query: CFDictionaryRef,
        operation: String,
    ): PendingFakeCall {
        assertTrue(keychainCfStringEquals(CFDictionaryGetValue(query, kSecClass), kSecClassGenericPassword))
        assertEquals(
            PENDING_SERVICE,
            PendingTestCf.text(query, kSecAttrService),
            "Only the isolated test service is allowed",
        )
        val rawContext = assertNotNull(CFDictionaryGetValue(query, kSecUseAuthenticationContext))
        val context = CFBridgingRelease(CFRetain(rawContext)) as? LAContext
        assertTrue(context?.interactionNotAllowed == true, "Never permit native authentication UI")
        val account = PendingTestCf.text(query, kSecAttrAccount)
        val data = PendingTestCf.flag(query, kSecReturnData)
        val limit = if (operation == "read") PendingTestCf.number(query, kSecMatchLimit) else null
        if (operation == "read") {
            assertTrue(
                keychainCfStringEquals(CFDictionaryGetValue(query, kSecAttrSynchronizable), kSecAttrSynchronizableAny),
            )
            assertEquals(null, CFDictionaryGetValue(query, kSecAttrAccessGroup))
            assertEquals(null, CFDictionaryGetValue(query, kSecAttrAccessible))
            assertTrue(PendingTestCf.flag(query, kSecReturnAttributes))
            assertEquals(if (account == null) 17 else 2, limit)
            if (account != null) assertFalse(data, "Recovery account checks must not fetch data")
        } else {
            assertNotNull(account)
            assertTrue(account.startsWith("slot.") && account.length <= 128)
            assertTrue(keychainCfBooleanEquals(CFDictionaryGetValue(query, kSecAttrSynchronizable), kCFBooleanFalse))
            assertTrue(
                keychainCfStringEquals(
                    CFDictionaryGetValue(query, kSecAttrAccessible),
                    kSecAttrAccessibleWhenUnlockedThisDeviceOnly,
                ),
            )
            if (operation == "add") {
                assertEquals(null, CFDictionaryGetValue(query, kSecAttrAccessGroup))
                assertTrue(canonicalInstallationUuid(account.removePrefix("slot.")))
            } else {
                assertEquals(PENDING_GROUP, PendingTestCf.text(query, kSecAttrAccessGroup))
            }
        }
        return PendingFakeCall(operation, account, data, limit).also { calls += it }
    }

    private fun matches(query: CFDictionaryRef): List<PendingFakeItem> {
        val service = PendingTestCf.text(query, kSecAttrService)
        val account = PendingTestCf.text(query, kSecAttrAccount)
        val group = PendingTestCf.text(query, kSecAttrAccessGroup)
        val anySync =
            keychainCfStringEquals(CFDictionaryGetValue(query, kSecAttrSynchronizable), kSecAttrSynchronizableAny)
        val accessible = CFDictionaryGetValue(query, kSecAttrAccessible)
        return items.filter {
            it.service == service &&
                (account == null || it.account == account) &&
                (group == null || it.group == group) &&
                (anySync || !it.sync) &&
                (accessible == null || keychainCfStringEquals(accessible, it.accessible))
        }
    }
}

private class PendingFakeCall(
    val operation: String,
    val account: String?,
    val includeData: Boolean,
    val limit: Int?,
)

private class PendingFakeItem(
    var account: String,
    var bytes: ByteArray,
) {
    var service = PENDING_SERVICE
    var group = PENDING_GROUP
    var sync = false
    var accessible = kSecAttrAccessibleWhenUnlockedThisDeviceOnly
    var editAttributes: ((CFMutableDictionaryRef) -> Unit)? = null

    fun attributes(includeData: Boolean): CFMutableDictionaryRef {
        val dictionary = PendingTestCf.dictionary()
        PendingTestCf.putString(dictionary, kSecAttrService, service)
        PendingTestCf.putString(dictionary, kSecAttrAccount, account)
        PendingTestCf.putString(dictionary, kSecAttrAccessGroup, group)
        CFDictionarySetValue(dictionary, kSecClass, kSecClassGenericPassword)
        CFDictionarySetValue(dictionary, kSecAttrAccessible, accessible)
        CFDictionarySetValue(dictionary, kSecAttrSynchronizable, if (sync) kCFBooleanTrue else kCFBooleanFalse)
        if (includeData) PendingTestCf.putData(dictionary, bytes)
        editAttributes?.invoke(dictionary)
        return dictionary
    }

    override fun toString(): String = "PendingFakeItem(redacted)"
}

private object PendingAttributeDefects {
    fun fixedAndAccount(): List<(PendingFakeItem) -> Unit> =
        listOf(
            { it.group = PENDING_OTHER_GROUP },
            { it.sync = true },
            { it.accessible = kSecAttrAccessibleAfterFirstUnlock },
            { it.account = "outside-prefix" },
            { it.account = "slot." + "x".repeat(124) },
            { it.account = "slot." + "\uD83D\uDE00".repeat(62) },
            { it.editAttributes = { attrs -> PendingTestCf.putString(attrs, kSecAttrService, "other-service") } },
            { it.editAttributes = { attrs -> CFDictionaryRemoveValue(attrs, kSecAttrService) } },
            { it.editAttributes = { attrs -> CFDictionarySetValue(attrs, kSecAttrService, kCFBooleanTrue) } },
            { it.editAttributes = { attrs -> CFDictionaryRemoveValue(attrs, kSecAttrAccount) } },
            { it.editAttributes = { attrs -> CFDictionarySetValue(attrs, kSecAttrAccount, kCFBooleanTrue) } },
            { it.editAttributes = { attrs -> CFDictionaryRemoveValue(attrs, kSecAttrAccessGroup) } },
            { it.editAttributes = { attrs -> CFDictionarySetValue(attrs, kSecAttrAccessGroup, kCFBooleanTrue) } },
            { it.editAttributes = { attrs -> CFDictionaryRemoveValue(attrs, kSecAttrAccessible) } },
            { it.editAttributes = { attrs -> CFDictionarySetValue(attrs, kSecAttrAccessible, kCFBooleanTrue) } },
            { it.editAttributes = { attrs -> CFDictionaryRemoveValue(attrs, kSecAttrSynchronizable) } },
            { it.editAttributes = { attrs -> PendingTestCf.putString(attrs, kSecAttrSynchronizable, "false") } },
            { it.editAttributes = { attrs -> CFDictionarySetValue(attrs, kSecClass, kSecClassInternetPassword) } },
        )
}

private object PendingTestCf {
    fun dictionary(): CFMutableDictionaryRef =
        assertNotNull(
            CFDictionaryCreateMutable(null, 0, kCFTypeDictionaryKeyCallBacks.ptr, kCFTypeDictionaryValueCallBacks.ptr),
        )

    fun putString(
        dictionary: CFMutableDictionaryRef,
        key: CFStringRef?,
        value: String,
    ) {
        val bytes = value.encodeToByteArray()
        // Explicit byte length, not CString: embedded NUL must reach the adapter unchanged.
        val string =
            assertNotNull(
                if (bytes.isEmpty()) {
                    CFStringCreateWithBytes(null, null, 0, kCFStringEncodingUTF8, false)
                } else {
                    bytes.usePinned {
                        CFStringCreateWithBytes(
                            null,
                            it.addressOf(0).reinterpret(),
                            bytes.size.toLong(),
                            kCFStringEncodingUTF8,
                            false,
                        )
                    }
                },
            )
        try {
            CFDictionarySetValue(dictionary, key, string)
        } finally {
            CFRelease(string)
        }
    }

    fun text(
        dictionary: CFDictionaryRef,
        key: CFStringRef?,
    ): String? {
        val raw = CFDictionaryGetValue(dictionary, key) ?: return null
        assertEquals(CFStringGetTypeID(), CFGetTypeID(raw))
        val string: CFStringRef = raw.reinterpret()
        val length = CFStringGetLength(string)
        assertTrue(length in 0L..4096L)
        return buildString(length.toInt()) {
            for (index in 0L until length) append(CFStringGetCharacterAtIndex(string, index).toInt().toChar())
        }
    }

    fun putData(
        dictionary: CFMutableDictionaryRef,
        bytes: ByteArray,
    ) {
        val data =
            assertNotNull(
                if (bytes.isEmpty()) {
                    CFDataCreate(null, null, 0)
                } else {
                    bytes.usePinned { CFDataCreate(null, it.addressOf(0).reinterpret(), bytes.size.toLong()) }
                },
            )
        try {
            CFDictionarySetValue(dictionary, kSecValueData, data)
        } finally {
            CFRelease(data)
        }
    }

    fun dataBytes(dictionary: CFDictionaryRef): ByteArray {
        val raw = assertNotNull(CFDictionaryGetValue(dictionary, kSecValueData))
        assertEquals(CFDataGetTypeID(), CFGetTypeID(raw))
        val data: CFDataRef = raw.reinterpret()
        val length = CFDataGetLength(data)
        assertTrue(length in 0L..2048L)
        return if (length == 0L) {
            ByteArray(0)
        } else {
            assertNotNull(CFDataGetBytePtr(data)).reinterpret<ByteVar>().readBytes(length.toInt())
        }
    }

    fun flag(
        dictionary: CFDictionaryRef,
        key: CFStringRef?,
    ): Boolean {
        val raw = CFDictionaryGetValue(dictionary, key) ?: return false
        return keychainCfBooleanEquals(raw, kCFBooleanTrue)
    }

    fun number(
        dictionary: CFDictionaryRef,
        key: CFStringRef?,
    ): Int =
        memScoped {
            val raw = assertNotNull(CFDictionaryGetValue(dictionary, key))
            assertEquals(CFNumberGetTypeID(), CFGetTypeID(raw), "Match limits must be numeric CFNumber values")
            val number = alloc<IntVar>()
            assertTrue(CFNumberGetValue(raw.reinterpret(), kCFNumberIntType, number.ptr))
            number.value
        }
}

private fun pendingId(index: Int = 1): String = "10000000-0000-4000-8000-${index.toString().padStart(12, '0')}"

private fun pendingSlot(
    index: Int = 1,
    bytes: ByteArray = byteArrayOf(1, 2, 3),
): PendingComplaintSlot {
    val result = PendingComplaintSlot.checked(pendingId(index), bytes)
    return assertIs<InstallationValueResult.Valid<PendingComplaintSlot>>(result).value
}

private fun pendingItem(slot: PendingComplaintSlot): PendingFakeItem = PendingFakeItem("slot.${slot.id}", slot.bytes())

private fun pendingStore(
    api: PendingSecItemFake,
    group: String = PENDING_GROUP,
): IosPendingComplaintActionStore = IosPendingComplaintActionStore.isolatedForTest(group, PENDING_SERVICE, api)

private fun assertInventory(
    result: PendingReadResult,
    vararg expected: PendingComplaintSlot,
) {
    val actual = assertIs<PendingReadResult.Verified>(result).snapshot.entries()
    assertEquals(expected.size, actual.size)
    assertTrue(expected.all { wanted -> actual.any { it.sameAs(wanted) } }, "Exact pending inventory mismatch")
}

private fun pendingFailure(
    result: Any,
    expected: Permanent,
) {
    assertEquals(expected, assertIs<Refused>(result).reason)
}

private fun pendingUnavailable(
    result: Any,
    expected: Temporary,
) {
    assertEquals(expected, assertIs<Unavailable>(result).reason)
}

private const val PENDING_PRODUCTION_SERVICE = "me.manga.kira.complaint.pending.v1"
private const val PENDING_SERVICE = "$PENDING_PRODUCTION_SERVICE.test.10000000-0000-4000-8000-000000000099"
private const val PENDING_GROUP = "TESTPREFIX.me.manga.kira"
private const val PENDING_OTHER_GROUP = "OTHERPREFIX.me.manga.kira"
