@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)

package me.manga.kira.platform.storage

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.readBytes
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import platform.CoreFoundation.CFArrayGetCount
import platform.CoreFoundation.CFArrayGetTypeID
import platform.CoreFoundation.CFArrayGetValueAtIndex
import platform.CoreFoundation.CFArrayRef
import platform.CoreFoundation.CFBooleanGetTypeID
import platform.CoreFoundation.CFDataCreate
import platform.CoreFoundation.CFDataGetBytePtr
import platform.CoreFoundation.CFDataGetLength
import platform.CoreFoundation.CFDataGetTypeID
import platform.CoreFoundation.CFDataRef
import platform.CoreFoundation.CFDictionaryCreateMutable
import platform.CoreFoundation.CFDictionaryGetTypeID
import platform.CoreFoundation.CFDictionaryGetValue
import platform.CoreFoundation.CFDictionaryRef
import platform.CoreFoundation.CFDictionarySetValue
import platform.CoreFoundation.CFGetTypeID
import platform.CoreFoundation.CFMutableDictionaryRef
import platform.CoreFoundation.CFNumberCreate
import platform.CoreFoundation.CFRelease
import platform.CoreFoundation.CFStringCreateWithCString
import platform.CoreFoundation.CFStringGetCharacterAtIndex
import platform.CoreFoundation.CFStringGetLength
import platform.CoreFoundation.CFStringGetTypeID
import platform.CoreFoundation.CFStringHasPrefix
import platform.CoreFoundation.CFStringRef
import platform.CoreFoundation.CFTypeRef
import platform.CoreFoundation.CFTypeRefVar
import platform.CoreFoundation.kCFBooleanFalse
import platform.CoreFoundation.kCFBooleanTrue
import platform.CoreFoundation.kCFNumberIntType
import platform.CoreFoundation.kCFStringEncodingUTF8
import platform.CoreFoundation.kCFTypeDictionaryKeyCallBacks
import platform.CoreFoundation.kCFTypeDictionaryValueCallBacks
import platform.Foundation.CFBridgingRetain
import platform.LocalAuthentication.LAContext
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
import kotlin.coroutines.CoroutineContext

/**
 * Inert opaque-slot adapter, not a request/ownership/prose validator or a cleanup authorization issuer.
 * The required default group needs independent signing provenance; its syntax check is not proof.
 * A shared pending-store mutex provides single-process CAS, never a multi-item Keychain transaction.
 * Numeric limits bound result count, not Security's internal CF allocation of an oversized item.
 */
class IosPendingComplaintActionStore private constructor(
    private val expectedDefaultAccessGroup: String,
    private val service: String,
    private val api: IosInstallationSecItemApi,
) : PendingComplaintActionStore {
    constructor(expectedDefaultAccessGroup: String) : this(
        expectedDefaultAccessGroup,
        SERVICE,
        IosNativeInstallationSecItemApi,
    )

    override suspend fun read(): PendingReadResult =
        serialized<PendingReadResult>({ it }) { access ->
            PendingReadResult.Verified(access.snapshot())
        }

    override suspend fun createIfMissing(slot: PendingComplaintSlot): PendingCreateResult =
        serialized<PendingCreateResult>({ it }) { PendingMutations.create(it, slot) }

    override suspend fun replace(
        expected: PendingComplaintSlot,
        replacement: PendingComplaintSlot,
    ): PendingReplaceResult =
        serialized<PendingReplaceResult>({ it }) { access ->
            PendingMutations.replace(access, expected, replacement)
        }

    override suspend fun delete(expected: PendingComplaintSlot): PendingDeleteResult =
        serialized<PendingDeleteResult>({ it }) { PendingMutations.delete(it, expected) }

    override suspend fun clearForConfirmedRecovery(): PendingClearResult =
        serialized<PendingClearResult>({ it }) { PendingRecovery.clear(it) }

    private suspend fun <T> serialized(
        failed: (InstallationStorageFailure) -> T,
        operation: (PendingKeychainAccess) -> T,
    ): T =
        withContext(Dispatchers.Default) {
            PROCESS_LOCK.withLock {
                try {
                    if (!PendingItemPolicy.validGroup(expectedDefaultAccessGroup)) {
                        pendingPermanent(InstallationPermanentFailure.UNSUPPORTED)
                    }
                    val context = currentCoroutineContext()
                    context.ensureActive()
                    operation(PendingKeychainAccess(api, service, expectedDefaultAccessGroup, context))
                        .also { context.ensureActive() }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (refused: PendingKeychainRefusal) {
                    failed(refused.failure)
                } catch (_: Exception) {
                    failed(InstallationStorageFailure.TemporarilyUnavailable(InstallationTemporaryFailure.UNCERTAIN))
                }
            }
        }

    companion object {
        private const val SERVICE = "me.manga.kira.complaint.pending.v1"
        private val PROCESS_LOCK = Mutex()

        internal fun isolatedForTest(
            expectedDefaultAccessGroup: String,
            isolatedService: String,
            api: IosInstallationSecItemApi = IosNativeInstallationSecItemApi,
        ): IosPendingComplaintActionStore {
            val prefix = "$SERVICE.test."
            require(
                isolatedService.startsWith(prefix) && canonicalInstallationUuid(isolatedService.removePrefix(prefix)),
            ) { "An isolated pending test namespace is required" }
            return IosPendingComplaintActionStore(expectedDefaultAccessGroup, isolatedService, api)
        }
    }
}

private const val ACCOUNT_PREFIX = "slot."
private const val RECOVERY_LIMIT = PendingComplaintSnapshot.MAX_SLOTS + 1
private const val ACCOUNT_MATCH_LIMIT = 2
private const val MAX_ACCOUNT_UNITS = 128
private const val UUID_UNITS = 36

private object PendingMutations {
    fun create(
        access: PendingKeychainAccess,
        slot: PendingComplaintSlot,
    ): PendingCreateResult {
        val before = access.snapshot().entries()
        return if (before.any { it.id == slot.id }) {
            PendingCreateResult.AlreadyPresent
        } else {
            admit(before + slot)
            when (val status = access.add(slot)) {
                errSecSuccess -> {
                    verify(before + slot, access.snapshot())
                    PendingCreateResult.Stored
                }
                errSecDuplicateItem -> {
                    val after = access.snapshot()
                    val winner =
                        after.entries().firstOrNull { it.id == slot.id }
                            ?: pendingPermanent(InstallationPermanentFailure.READ_BACK_MISMATCH)
                    verify(before + winner, after)
                    PendingCreateResult.AlreadyPresent
                }
                else -> pendingStatus(status)
            }
        }
    }

    fun replace(
        access: PendingKeychainAccess,
        expected: PendingComplaintSlot,
        replacement: PendingComplaintSlot,
    ): PendingReplaceResult {
        if (expected.id != replacement.id) pendingPermanent(InstallationPermanentFailure.STATE_CHANGED)
        val before = access.snapshot().entries()
        val current = before.firstOrNull { it.id == expected.id }
        return when {
            current == null -> PendingReplaceResult.Missing
            !current.sameAs(expected) -> PendingReplaceResult.Stale
            else -> replacePresent(access, before, replacement)
        }
    }

    fun delete(
        access: PendingKeychainAccess,
        expected: PendingComplaintSlot,
    ): PendingDeleteResult {
        val before = access.snapshot().entries()
        val current = before.firstOrNull { it.id == expected.id }
        return when {
            current == null -> PendingDeleteResult.Missing
            !current.sameAs(expected) -> PendingDeleteResult.Stale
            else -> deletePresent(access, before, expected.id)
        }
    }

    private fun replacePresent(
        access: PendingKeychainAccess,
        before: List<PendingComplaintSlot>,
        replacement: PendingComplaintSlot,
    ): PendingReplaceResult {
        val desired = before.map { if (it.id == replacement.id) replacement else it }
        admit(desired)
        return when (val status = access.update(replacement)) {
            errSecSuccess -> {
                verify(desired, access.snapshot())
                PendingReplaceResult.Stored
            }
            errSecItemNotFound -> {
                val after = access.snapshot()
                val current = after.entries().firstOrNull { it.id == replacement.id }
                verify(before.filterNot { it.id == replacement.id } + listOfNotNull(current), after)
                if (current == null) PendingReplaceResult.Missing else PendingReplaceResult.Stale
            }
            else -> pendingStatus(status)
        }
    }

    private fun deletePresent(
        access: PendingKeychainAccess,
        before: List<PendingComplaintSlot>,
        id: String,
    ): PendingDeleteResult {
        val status = access.delete(id)
        if (status != errSecSuccess && status != errSecItemNotFound) pendingStatus(status)
        val after = access.snapshot()
        val current = after.entries().firstOrNull { it.id == id }
        val remaining = before.filterNot { it.id == id }
        if (status == errSecItemNotFound && current != null) {
            verify(remaining + current, after)
            return PendingDeleteResult.Stale
        }
        verify(remaining, after)
        return if (status == errSecSuccess) PendingDeleteResult.Deleted else PendingDeleteResult.Missing
    }

    private fun admit(slots: List<PendingComplaintSlot>) {
        if (slots.size > PendingComplaintSnapshot.MAX_SLOTS ||
            slots.sumOf { it.size } > PendingComplaintSnapshot.MAX_LOGICAL_BYTES
        ) {
            pendingPermanent(InstallationPermanentFailure.TOO_LARGE)
        }
    }

    private fun verify(
        expected: List<PendingComplaintSlot>,
        actual: PendingComplaintSnapshot,
    ) {
        val entries = actual.entries()
        if (expected.size != entries.size || !expected.all { wanted -> entries.any { it.sameAs(wanted) } }) {
            pendingPermanent(InstallationPermanentFailure.READ_BACK_MISMATCH)
        }
    }
}

private object PendingRecovery {
    fun clear(access: PendingKeychainAccess): PendingClearResult {
        val hadItems =
            access.recoveryBatch { accounts ->
                for (account in accounts) {
                    if (access.hasRecoverable(account)) {
                        val status = access.deleteAccount(account)
                        if (status != errSecSuccess && status != errSecItemNotFound) pendingStatus(status)
                    }
                    if (access.hasRecoverable(account)) {
                        pendingPermanent(InstallationPermanentFailure.READ_BACK_MISMATCH)
                    }
                }
                accounts.isNotEmpty()
            }
        if (!hadItems) return PendingClearResult.Cleared
        return access.recoveryBatch { remaining ->
            when {
                remaining.isEmpty() -> PendingClearResult.Cleared
                remaining.size > PendingComplaintSnapshot.MAX_SLOTS ->
                    pendingPermanent(InstallationPermanentFailure.TOO_LARGE)
                else -> pendingTemporary(InstallationTemporaryFailure.UNCERTAIN)
            }
        }
    }
}

private class PendingKeychainAccess(
    private val api: IosInstallationSecItemApi,
    private val service: String,
    private val group: String,
    private val context: CoroutineContext,
) {
    fun snapshot(): PendingComplaintSnapshot =
        copy(null, includeData = true, limit = RECOVERY_LIMIT) { PendingInventory.snapshot(it, service, group) }

    fun <T> recoveryBatch(operation: (List<CFStringRef>) -> T): T =
        copy(null, includeData = false, limit = RECOVERY_LIMIT) {
            // Account references remain borrowed from this retained result throughout the callback.
            operation(PendingInventory.recovery(it, service, group))
        }

    fun hasRecoverable(account: CFStringRef): Boolean =
        copy(account, includeData = false, limit = ACCOUNT_MATCH_LIMIT) {
            val accounts = PendingInventory.recovery(it, service, group)
            if (accounts.size > 1 || (accounts.size == 1 && !keychainCfStringEquals(accounts.single(), account))) {
                pendingPermanent(InstallationPermanentFailure.STATE_CHANGED)
            }
            accounts.isNotEmpty()
        }

    fun add(slot: PendingComplaintSlot): Int =
        PendingCf.string(ACCOUNT_PREFIX + slot.id) { account ->
            PendingQueries.add(service, account) { attributes ->
                PendingCf.putSlot(attributes, slot)
                context.ensureActive()
                api.add(attributes).also { context.ensureActive() }
            }
        }

    fun update(slot: PendingComplaintSlot): Int =
        PendingCf.string(ACCOUNT_PREFIX + slot.id) { account ->
            PendingQueries.mutation(service, group, account) { query ->
                PendingCf.dictionary { attributes ->
                    PendingCf.putSlot(attributes, slot)
                    context.ensureActive()
                    api.update(query, attributes).also { context.ensureActive() }
                }
            }
        }

    fun delete(id: String): Int = PendingCf.string(ACCOUNT_PREFIX + id, ::deleteAccount)

    fun deleteAccount(account: CFStringRef): Int =
        PendingQueries.mutation(service, group, account) { query ->
            context.ensureActive()
            api.delete(query).also { context.ensureActive() }
        }

    private fun <T> copy(
        account: CFStringRef?,
        includeData: Boolean,
        limit: Int,
        operation: (CFArrayRef?) -> T,
    ): T =
        PendingQueries.read(service, account, includeData, limit) { query ->
            memScoped {
                val result = alloc<CFTypeRefVar>().also { it.value = null }
                try {
                    context.ensureActive()
                    val status = api.copyMatching(query, result.ptr)
                    context.ensureActive()
                    when (status) {
                        errSecItemNotFound -> operation(null)
                        errSecSuccess -> operation(PendingInventory.array(result.value, limit))
                        else -> pendingStatus(status)
                    }
                } finally {
                    result.value?.let { CFRelease(it) }
                }
            }
        }
}

private object PendingQueries {
    fun <T> read(
        service: String,
        account: CFStringRef?,
        includeData: Boolean,
        limit: Int,
        operation: (CFDictionaryRef) -> T,
    ): T =
        PendingCf.dictionary { query ->
            identity(query, service, account)
            // Broad reads must expose shared-group, synced and misprotected conflicts.
            CFDictionarySetValue(query, kSecAttrSynchronizable, kSecAttrSynchronizableAny)
            CFDictionarySetValue(query, kSecReturnAttributes, kCFBooleanTrue)
            if (includeData) CFDictionarySetValue(query, kSecReturnData, kCFBooleanTrue)
            matchLimit(query, limit)
            operation(query)
        }

    fun <T> add(
        service: String,
        account: CFStringRef,
        operation: (CFMutableDictionaryRef) -> T,
    ): T =
        PendingCf.dictionary { attributes ->
            identity(attributes, service, account)
            protection(attributes)
            // OS chooses the default only on Add; broad read-back must prove the supplied group.
            operation(attributes)
        }

    fun <T> mutation(
        service: String,
        group: String,
        account: CFStringRef,
        operation: (CFDictionaryRef) -> T,
    ): T =
        PendingCf.dictionary { query ->
            identity(query, service, account)
            protection(query)
            PendingCf.putString(query, kSecAttrAccessGroup, group)
            operation(query)
        }

    private fun identity(
        query: CFMutableDictionaryRef,
        service: String,
        account: CFStringRef?,
    ) {
        CFDictionarySetValue(query, kSecClass, kSecClassGenericPassword)
        PendingCf.putString(query, kSecAttrService, service)
        // Never re-encode a recovered account: embedded NUL and malformed suffixes remain exact.
        if (account != null) CFDictionarySetValue(query, kSecAttrAccount, account)
        val authentication = LAContext().also { it.interactionNotAllowed = true }
        val reference = CFBridgingRetain(authentication) ?: pendingTemporary(InstallationTemporaryFailure.IO_FAILURE)
        try {
            CFDictionarySetValue(query, kSecUseAuthenticationContext, reference)
        } finally {
            CFRelease(reference)
        }
    }

    private fun protection(query: CFMutableDictionaryRef) {
        CFDictionarySetValue(query, kSecAttrAccessible, kSecAttrAccessibleWhenUnlockedThisDeviceOnly)
        CFDictionarySetValue(query, kSecAttrSynchronizable, kCFBooleanFalse)
    }

    private fun matchLimit(
        query: CFMutableDictionaryRef,
        limit: Int,
    ) = memScoped {
        val maximum = alloc<IntVar>().also { it.value = limit }
        val number =
            CFNumberCreate(null, kCFNumberIntType, maximum.ptr)
                ?: pendingTemporary(InstallationTemporaryFailure.IO_FAILURE)
        try {
            CFDictionarySetValue(query, kSecMatchLimit, number)
        } finally {
            CFRelease(number)
        }
    }
}

private object PendingInventory {
    fun array(
        raw: CFTypeRef?,
        limit: Int,
    ): CFArrayRef {
        if (raw == null || CFGetTypeID(raw) != CFArrayGetTypeID()) {
            pendingPermanent(InstallationPermanentFailure.STATE_CHANGED)
        }
        val array: CFArrayRef = raw.reinterpret()
        val count = CFArrayGetCount(array)
        if (count > limit) pendingPermanent(InstallationPermanentFailure.TOO_LARGE)
        // Successful empty/malformed results are not the native NotFound absence proof.
        if (count <= 0) pendingPermanent(InstallationPermanentFailure.STATE_CHANGED)
        return array
    }

    fun snapshot(
        array: CFArrayRef?,
        service: String,
        group: String,
    ): PendingComplaintSnapshot {
        if (array == null) return pendingValue(PendingComplaintSnapshot.checked(emptyList()))
        val count = CFArrayGetCount(array).toInt()
        if (count > PendingComplaintSnapshot.MAX_SLOTS) pendingPermanent(InstallationPermanentFailure.TOO_LARGE)
        val borrowed =
            (0 until count).map { index ->
                val attributes = dictionary(array, index)
                val account = PendingItemPolicy.account(attributes, service, group)
                if (CFStringGetLength(account) != (ACCOUNT_PREFIX.length + UUID_UNITS).toLong()) {
                    pendingPermanent(InstallationPermanentFailure.STATE_CHANGED)
                }
                borrowedSlot(account, attributes)
            }
        if (borrowed.sumOf { it.length } > PendingComplaintSnapshot.MAX_LOGICAL_BYTES) {
            pendingPermanent(InstallationPermanentFailure.TOO_LARGE)
        }
        // Admit every native length and the aggregate before copying any account or payload to Kotlin.
        val ids = borrowed.map { PendingCf.accountText(it.account).removePrefix(ACCOUNT_PREFIX) }
        if (ids.any { !canonicalInstallationUuid(it) } || ids.distinct().size != ids.size) {
            pendingPermanent(InstallationPermanentFailure.STATE_CHANGED)
        }
        val slots =
            borrowed.mapIndexed { index, slot ->
                val bytes = PendingCf.dataBytes(slot.data, slot.length)
                try {
                    pendingValue(PendingComplaintSlot.checked(ids[index], bytes))
                } finally {
                    bytes.fill(0)
                }
            }
        return pendingValue(PendingComplaintSnapshot.checked(slots))
    }

    fun recovery(
        array: CFArrayRef?,
        service: String,
        group: String,
    ): List<CFStringRef> {
        if (array == null) return emptyList()
        val accounts = mutableListOf<CFStringRef>()
        for (index in 0 until CFArrayGetCount(array).toInt()) {
            val account = PendingItemPolicy.account(dictionary(array, index), service, group)
            if (accounts.any { keychainCfStringEquals(it, account) }) {
                pendingPermanent(InstallationPermanentFailure.STATE_CHANGED)
            }
            accounts += account
        }
        // The caller cannot start deletion until this entire bounded attribute-only batch is admitted.
        return accounts
    }

    private fun dictionary(
        array: CFArrayRef,
        index: Int,
    ): CFDictionaryRef {
        val raw =
            CFArrayGetValueAtIndex(array, index.toLong())
                ?: pendingPermanent(InstallationPermanentFailure.STATE_CHANGED)
        if (CFGetTypeID(raw) != CFDictionaryGetTypeID()) pendingPermanent(InstallationPermanentFailure.STATE_CHANGED)
        return raw.reinterpret()
    }

    private fun borrowedSlot(
        account: CFStringRef,
        attributes: CFDictionaryRef,
    ): PendingBorrowedSlot {
        val raw = CFDictionaryGetValue(attributes, kSecValueData)
        if (raw == null || CFGetTypeID(raw) != CFDataGetTypeID()) {
            pendingPermanent(InstallationPermanentFailure.CORRUPT)
        }
        val data: CFDataRef = raw.reinterpret()
        val length = CFDataGetLength(data)
        if (length > PendingComplaintSlot.MAX_BYTES) pendingPermanent(InstallationPermanentFailure.TOO_LARGE)
        if (length < 0 || (length > 0 && CFDataGetBytePtr(data) == null)) {
            pendingPermanent(InstallationPermanentFailure.CORRUPT)
        }
        return PendingBorrowedSlot(account, data, length.toInt())
    }
}

/** Borrowed references cannot escape the retained native result callback. */
private class PendingBorrowedSlot(
    val account: CFStringRef,
    val data: CFDataRef,
    val length: Int,
)

private object PendingItemPolicy {
    private const val MAX_GROUP_LENGTH = 255

    fun validGroup(value: String): Boolean =
        value.length in 1..MAX_GROUP_LENGTH &&
            value.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it == '.' || it == '-' } &&
            value.split('.').all { it.isNotEmpty() }

    fun account(
        attributes: CFDictionaryRef,
        service: String,
        group: String,
    ): CFStringRef {
        requireFixed(attributes, service, group)
        val raw = CFDictionaryGetValue(attributes, kSecAttrAccount)
        if (raw == null || CFGetTypeID(raw) != CFStringGetTypeID()) {
            pendingPermanent(InstallationPermanentFailure.STATE_CHANGED)
        }
        val account: CFStringRef = raw.reinterpret()
        val length = CFStringGetLength(account)
        if (length < ACCOUNT_PREFIX.length || length > MAX_ACCOUNT_UNITS) {
            pendingPermanent(InstallationPermanentFailure.STATE_CHANGED)
        }
        if (!PendingCf.string(ACCOUNT_PREFIX) { CFStringHasPrefix(account, it) }) {
            pendingPermanent(InstallationPermanentFailure.STATE_CHANGED)
        }
        return account
    }

    private fun requireFixed(
        attributes: CFDictionaryRef,
        service: String,
        group: String,
    ) {
        val itemClass = CFDictionaryGetValue(attributes, kSecClass)
        val expected =
            PendingCf.matches(attributes, kSecAttrService, service) &&
                PendingCf.matches(attributes, kSecAttrAccessGroup, group) &&
                (itemClass == null || keychainCfStringEquals(itemClass, kSecClassGenericPassword))
        if (!expected) pendingPermanent(InstallationPermanentFailure.STATE_CHANGED)
        requireProtection(attributes)
    }

    private fun requireProtection(attributes: CFDictionaryRef) {
        val accessible = CFDictionaryGetValue(attributes, kSecAttrAccessible)
        val sync = CFDictionaryGetValue(attributes, kSecAttrSynchronizable)
        val expected =
            accessible != null &&
                CFGetTypeID(accessible) == CFStringGetTypeID() &&
                keychainCfStringEquals(accessible, kSecAttrAccessibleWhenUnlockedThisDeviceOnly) &&
                sync != null &&
                CFGetTypeID(sync) == CFBooleanGetTypeID() &&
                keychainCfBooleanEquals(sync, kCFBooleanFalse)
        if (!expected) pendingPermanent(InstallationPermanentFailure.STATE_CHANGED)
    }
}

private object PendingCf {
    fun <T> dictionary(operation: (CFMutableDictionaryRef) -> T): T {
        val dictionary =
            CFDictionaryCreateMutable(
                null,
                0,
                kCFTypeDictionaryKeyCallBacks.ptr,
                kCFTypeDictionaryValueCallBacks.ptr,
            ) ?: pendingTemporary(InstallationTemporaryFailure.IO_FAILURE)
        return try {
            operation(dictionary)
        } finally {
            CFRelease(dictionary)
        }
    }

    /** Only trusted service/group/generated canonical accounts use CString construction. */
    fun <T> string(
        value: String,
        operation: (CFStringRef) -> T,
    ): T {
        val string =
            CFStringCreateWithCString(null, value, kCFStringEncodingUTF8)
                ?: pendingTemporary(InstallationTemporaryFailure.IO_FAILURE)
        return try {
            operation(string)
        } finally {
            CFRelease(string)
        }
    }

    fun putString(
        dictionary: CFMutableDictionaryRef,
        key: CFStringRef?,
        value: String,
    ) {
        string(value) { CFDictionarySetValue(dictionary, key, it) }
    }

    fun matches(
        dictionary: CFDictionaryRef,
        key: CFStringRef?,
        expected: String,
    ): Boolean {
        val actual = CFDictionaryGetValue(dictionary, key)
        return actual != null &&
            CFGetTypeID(actual) == CFStringGetTypeID() &&
            string(expected) { keychainCfStringEquals(actual, it) }
    }

    fun putSlot(
        dictionary: CFMutableDictionaryRef,
        slot: PendingComplaintSlot,
    ) {
        val bytes = slot.bytes()
        try {
            val data =
                if (bytes.isEmpty()) {
                    CFDataCreate(null, null, 0)
                } else {
                    bytes.usePinned { CFDataCreate(null, it.addressOf(0).reinterpret(), bytes.size.toLong()) }
                } ?: pendingTemporary(InstallationTemporaryFailure.IO_FAILURE)
            try {
                CFDictionarySetValue(dictionary, kSecValueData, data)
            } finally {
                CFRelease(data)
            }
        } finally {
            // Best effort for the owned Kotlin copy, not a native/immutable-buffer erasure claim.
            bytes.fill(0)
        }
    }

    fun accountText(account: CFStringRef): String {
        val length = CFStringGetLength(account)
        if (length !in 0L..MAX_ACCOUNT_UNITS.toLong()) pendingPermanent(InstallationPermanentFailure.STATE_CHANGED)
        return buildString(length.toInt()) {
            for (index in 0L until length) append(CFStringGetCharacterAtIndex(account, index).toInt().toChar())
        }
    }

    fun dataBytes(
        data: CFDataRef,
        length: Int,
    ): ByteArray =
        if (length == 0) {
            ByteArray(0)
        } else {
            val pointer = CFDataGetBytePtr(data) ?: pendingPermanent(InstallationPermanentFailure.CORRUPT)
            pointer.reinterpret<ByteVar>().readBytes(length)
        }
}

private class PendingKeychainRefusal(
    val failure: InstallationStorageFailure,
) : Exception("Pending storage operation refused")

private fun pendingPermanent(reason: InstallationPermanentFailure): Nothing =
    throw PendingKeychainRefusal(InstallationStorageFailure.PermanentFailure(reason))

private fun pendingTemporary(reason: InstallationTemporaryFailure): Nothing =
    throw PendingKeychainRefusal(InstallationStorageFailure.TemporarilyUnavailable(reason))

private fun pendingStatus(status: Int): Nothing =
    when (status) {
        errSecInteractionNotAllowed, errSecInteractionRequired -> pendingTemporary(InstallationTemporaryFailure.LOCKED)
        errSecIO, errSecNotAvailable -> pendingTemporary(InstallationTemporaryFailure.IO_FAILURE)
        errSecDecode -> pendingPermanent(InstallationPermanentFailure.CORRUPT)
        errSecUnimplemented, errSecMissingEntitlement, errSecParam ->
            pendingPermanent(InstallationPermanentFailure.UNSUPPORTED)
        else -> pendingTemporary(InstallationTemporaryFailure.UNCERTAIN)
    }

private fun <T> pendingValue(result: InstallationValueResult<T>): T =
    when (result) {
        is InstallationValueResult.Valid -> result.value
        is InstallationValueResult.Invalid -> pendingPermanent(InstallationPermanentFailure.STATE_CHANGED)
    }
