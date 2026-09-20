@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)

package me.manga.kira.platform.storage

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CPointer
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
import platform.CoreFoundation.CFStringGetTypeID
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
import platform.Security.SecItemAdd
import platform.Security.SecItemCopyMatching
import platform.Security.SecItemDelete
import platform.Security.SecItemUpdate
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
 * Inert, single-process Keychain adapter; no DI, enrollment, pending-store or legacy integration.
 * The required group must come from independently verified signing entitlements, not a Team-ID guess.
 * Syntax validation is not provenance. Add requests the OS default group; read-back must match this input.
 * One process-wide lock covers every instance, but neither generation CAS nor two-item cleanup is an
 * interprocess/Keychain transaction. Only individual SecItem operations have native atomicity.
 */
class IosInstallationCredentialStore private constructor(
    private val expectedDefaultAccessGroup: String,
    private val service: String,
    private val api: IosInstallationSecItemApi,
) : InstallationCredentialStore {
    constructor(expectedDefaultAccessGroup: String) : this(
        expectedDefaultAccessGroup,
        SERVICE,
        IosNativeInstallationSecItemApi,
    )

    override suspend fun read(): CredentialReadResult =
        serialized<CredentialReadResult>({ it }) { access ->
            when (val slot = KeychainRecords.credential(access)) {
                CredentialSlot.Absent -> CredentialReadResult.Missing
                is CredentialSlot.Readable -> CredentialReadResult.Present(slot.record)
                is CredentialSlot.Unreadable -> permanent(slot.reason)
            }
        }

    override suspend fun createIfMissing(record: InstallationCredentialRecord): CredentialCreateResult =
        serialized<CredentialCreateResult>({ it }) { access ->
            KeychainPolicy.requireIos(record)
            if (!record.isInitialCandidate) permanent(InstallationPermanentFailure.STATE_CHANGED)
            KeychainRecords.noMarker(access)
            when (val slot = KeychainRecords.credential(access)) {
                is CredentialSlot.Readable -> CredentialCreateResult.AlreadyPresent
                is CredentialSlot.Unreadable -> permanent(slot.reason)
                CredentialSlot.Absent -> {
                    val status = encoded(InstallationCredentialCodec.encode(record)) { access.add(CREDENTIAL, it) }
                    when (status) {
                        errSecSuccess -> {
                            KeychainRecords.readBack(access, record)
                            KeychainRecords.noMarker(access)
                            CredentialCreateResult.Stored
                        }
                        errSecDuplicateItem -> {
                            KeychainRecords.readableWinner(access)
                            KeychainRecords.noMarker(access)
                            CredentialCreateResult.AlreadyPresent
                        }
                        else -> statusFailure(status)
                    }
                }
            }
        }

    override suspend fun replace(
        expectedGeneration: Long,
        record: InstallationCredentialRecord,
    ): CredentialReplaceResult =
        serialized<CredentialReplaceResult>({ it }) { access ->
            KeychainPolicy.requireIos(record)
            KeychainRecords.noMarker(access)
            when (val slot = KeychainRecords.credential(access)) {
                CredentialSlot.Absent -> CredentialReplaceResult.Missing
                is CredentialSlot.Unreadable -> permanent(slot.reason)
                is CredentialSlot.Readable -> {
                    val old = slot.record
                    if (old.localGeneration != expectedGeneration) {
                        CredentialReplaceResult.Stale
                    } else {
                        KeychainPolicy.requireReplacement(old, record)
                        val status =
                            encoded(InstallationCredentialCodec.encode(record)) { access.update(CREDENTIAL, it) }
                        when (status) {
                            errSecSuccess -> {
                                KeychainRecords.readBack(access, record)
                                KeychainRecords.noMarker(access)
                                CredentialReplaceResult.Stored
                            }
                            errSecItemNotFound ->
                                when (val current = KeychainRecords.credential(access)) {
                                    CredentialSlot.Absent -> CredentialReplaceResult.Missing
                                    is CredentialSlot.Readable -> CredentialReplaceResult.Stale
                                    is CredentialSlot.Unreadable -> permanent(current.reason)
                                }
                            else -> statusFailure(status)
                        }
                    }
                }
            }
        }

    override suspend fun delete(
        expectedGeneration: Long,
        expectedMarker: CredentialCleanupMarker,
    ): CredentialDeleteResult =
        serialized<CredentialDeleteResult>({ it }) { access ->
            if (expectedGeneration <= 0 || expectedMarker.expectedGeneration != expectedGeneration) {
                CredentialDeleteResult.Stale
            } else {
                KeychainRecords.finish(access, expectedMarker)
            }
        }

    override suspend fun resetUnreadableAfterConfirmation(
        // Unreadable reset requires the explicit null-generation confirmation marker.
        expectedMarker: CredentialCleanupMarker,
    ): CredentialResetResult =
        serialized<CredentialResetResult>({ it }) { access ->
            if (expectedMarker.expectedGeneration != null ||
                expectedMarker.reason != CredentialCleanupReason.UNREADABLE_RESET_CONFIRMED
            ) {
                permanent(InstallationPermanentFailure.STATE_CHANGED)
            }
            when (val result = KeychainRecords.finish(access, expectedMarker)) {
                CredentialDeleteResult.Deleted -> CredentialResetResult.Deleted
                CredentialDeleteResult.Missing -> CredentialResetResult.Missing
                CredentialDeleteResult.Stale -> permanent(InstallationPermanentFailure.STATE_CHANGED)
                is InstallationStorageFailure -> result
            }
        }

    override suspend fun finishMarkedCleanup(expectedMarker: CredentialCleanupMarker): CredentialDeleteResult =
        serialized<CredentialDeleteResult>({ it }) { KeychainRecords.finish(it, expectedMarker) }

    override suspend fun readCleanupMarker(): CleanupMarkerReadResult =
        serialized<CleanupMarkerReadResult>({ it }) { access ->
            KeychainRecords.readMarker(access)?.let { CleanupMarkerReadResult.Present(it) }
                ?: CleanupMarkerReadResult.Missing
        }

    override suspend fun createCleanupMarkerIfMissing(marker: CredentialCleanupMarker): CleanupMarkerCreateResult =
        serialized<CleanupMarkerCreateResult>({ it }) { access ->
            val existing = KeychainRecords.readMarker(access)
            if (existing != null) {
                KeychainPolicy.requireMarker(existing, marker)
                CleanupMarkerCreateResult.AlreadyPresent
            } else {
                val status = encoded(CredentialCleanupMarkerCodec.encode(marker)) { access.add(MARKER, it) }
                if (status != errSecSuccess && status != errSecDuplicateItem) statusFailure(status)
                val stored =
                    KeychainRecords.readMarker(access) ?: permanent(InstallationPermanentFailure.READ_BACK_MISMATCH)
                KeychainPolicy.requireMarker(stored, marker)
                if (status == errSecSuccess) {
                    CleanupMarkerCreateResult.Stored
                } else {
                    CleanupMarkerCreateResult.AlreadyPresent
                }
            }
        }

    override suspend fun removeCleanupMarker(expectedMarker: CredentialCleanupMarker): CleanupMarkerRemoveResult =
        serialized<CleanupMarkerRemoveResult>({ it }) { access ->
            KeychainRecords.absentCredential(access)
            val stored = KeychainRecords.readMarker(access)
            when {
                stored == null -> CleanupMarkerRemoveResult.Missing
                !stored.sameAs(expectedMarker) -> CleanupMarkerRemoveResult.Stale
                else -> {
                    val status = access.delete(MARKER)
                    if (status != errSecSuccess && status != errSecItemNotFound) statusFailure(status)
                    if (KeychainRecords.readMarker(access) != null) {
                        permanent(InstallationPermanentFailure.READ_BACK_MISMATCH)
                    }
                    if (status == errSecSuccess) {
                        CleanupMarkerRemoveResult.Removed
                    } else {
                        CleanupMarkerRemoveResult.Missing
                    }
                }
            }
        }

    private suspend fun <T> serialized(
        failed: (InstallationStorageFailure) -> T,
        operation: (KeychainAccess) -> T,
    ): T =
        withContext(Dispatchers.Default) {
            PROCESS_LOCK.withLock {
                try {
                    if (!KeychainPolicy.validAccessGroup(expectedDefaultAccessGroup)) {
                        permanent(InstallationPermanentFailure.UNSUPPORTED)
                    }
                    val context = currentCoroutineContext()
                    context.ensureActive()
                    operation(KeychainAccess(api, service, expectedDefaultAccessGroup, context))
                        .also { context.ensureActive() }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (refused: KeychainRefusal) {
                    failed(refused.failure)
                } catch (_: Exception) {
                    failed(InstallationStorageFailure.TemporarilyUnavailable(InstallationTemporaryFailure.UNCERTAIN))
                }
            }
        }

    companion object {
        private const val SERVICE = "me.manga.kira.complaint.installation.v1"
        private val PROCESS_LOCK = Mutex()

        /** Tests cannot select the production service, even when using the real Security API. */
        internal fun isolatedForTest(
            expectedDefaultAccessGroup: String,
            isolatedService: String,
            api: IosInstallationSecItemApi = IosNativeInstallationSecItemApi,
        ): IosInstallationCredentialStore {
            val prefix = "$SERVICE.test."
            require(
                isolatedService.startsWith(prefix) && canonicalInstallationUuid(isolatedService.removePrefix(prefix)),
            ) {
                "An isolated installation test namespace is required"
            }
            return IosInstallationCredentialStore(expectedDefaultAccessGroup, isolatedService, api)
        }
    }
}

private const val CREDENTIAL = "credential"
private const val MARKER = "cleanup-marker"

private object KeychainRecords {
    fun finish(
        access: KeychainAccess,
        expected: CredentialCleanupMarker,
    ): CredentialDeleteResult {
        val stored = readMarker(access)
        if (stored == null || !stored.sameAs(expected)) return CredentialDeleteResult.Stale
        return when (val slot = credential(access)) {
            CredentialSlot.Absent -> CredentialDeleteResult.Missing
            is CredentialSlot.Readable ->
                if (KeychainPolicy.matchesCleanup(slot.record, expected)) {
                    deleteVerifiedCredential(access)
                } else {
                    CredentialDeleteResult.Stale
                }
            // Only malformed/oversized payload under independently verified fixed attributes reaches this case.
            is CredentialSlot.Unreadable -> deleteVerifiedCredential(access)
        }
    }

    private fun deleteVerifiedCredential(access: KeychainAccess): CredentialDeleteResult {
        val status = access.delete(CREDENTIAL)
        if (status != errSecSuccess && status != errSecItemNotFound) statusFailure(status)
        absentCredential(access)
        return if (status == errSecSuccess) CredentialDeleteResult.Deleted else CredentialDeleteResult.Missing
    }

    fun credential(access: KeychainAccess): CredentialSlot {
        val payload =
            access.read(CREDENTIAL, InstallationCredentialCodec.MAX_ENCODED_BYTES) ?: return CredentialSlot.Absent
        return when (val decoded = decode(payload, InstallationCredentialCodec::decode)) {
            is InstallationCodecResult.Value -> {
                KeychainPolicy.requireIos(decoded.value)
                CredentialSlot.Readable(decoded.value)
            }
            InstallationCodecResult.Corrupt -> CredentialSlot.Unreadable(InstallationPermanentFailure.CORRUPT)
            InstallationCodecResult.TooLarge -> CredentialSlot.Unreadable(InstallationPermanentFailure.TOO_LARGE)
        }
    }

    fun readMarker(access: KeychainAccess): CredentialCleanupMarker? {
        val payload = access.read(MARKER, CredentialCleanupMarker.MAX_ENCODED_BYTES) ?: return null
        return when (val decoded = decode(payload, CredentialCleanupMarkerCodec::decode)) {
            is InstallationCodecResult.Value -> decoded.value
            InstallationCodecResult.Corrupt -> permanent(InstallationPermanentFailure.CORRUPT)
            InstallationCodecResult.TooLarge -> permanent(InstallationPermanentFailure.TOO_LARGE)
        }
    }

    fun readableWinner(access: KeychainAccess): InstallationCredentialRecord =
        when (val slot = credential(access)) {
            CredentialSlot.Absent -> permanent(InstallationPermanentFailure.READ_BACK_MISMATCH)
            is CredentialSlot.Readable -> slot.record
            is CredentialSlot.Unreadable -> permanent(slot.reason)
        }

    fun readBack(
        access: KeychainAccess,
        expected: InstallationCredentialRecord,
    ) {
        // Strict canonical decoding plus sameAs compares every field, not only generation or installation ID.
        if (!readableWinner(access).sameAs(expected)) permanent(InstallationPermanentFailure.READ_BACK_MISMATCH)
    }

    fun noMarker(access: KeychainAccess) {
        if (readMarker(access) != null) permanent(InstallationPermanentFailure.MARKER_CONFLICT)
    }

    fun absentCredential(access: KeychainAccess) {
        val payload = access.read(CREDENTIAL, InstallationCredentialCodec.MAX_ENCODED_BYTES) ?: return
        if (payload is KeychainPayload.Bytes) payload.bytes.fill(0)
        permanent(InstallationPermanentFailure.READ_BACK_MISMATCH)
    }
}

/** Four native calls only; fault tests exercise the real adapter and its CF query/result handling. */
internal interface IosInstallationSecItemApi {
    fun copyMatching(
        query: CFDictionaryRef,
        result: CPointer<CFTypeRefVar>,
    ): Int

    fun add(attributes: CFDictionaryRef): Int

    fun update(
        query: CFDictionaryRef,
        attributes: CFDictionaryRef,
    ): Int

    fun delete(query: CFDictionaryRef): Int
}

internal object IosNativeInstallationSecItemApi : IosInstallationSecItemApi {
    override fun copyMatching(
        query: CFDictionaryRef,
        result: CPointer<CFTypeRefVar>,
    ): Int = SecItemCopyMatching(query, result)

    override fun add(attributes: CFDictionaryRef): Int = SecItemAdd(attributes, null)

    override fun update(
        query: CFDictionaryRef,
        attributes: CFDictionaryRef,
    ): Int = SecItemUpdate(query, attributes)

    override fun delete(query: CFDictionaryRef): Int = SecItemDelete(query)
}

private class KeychainAccess(
    private val api: IosInstallationSecItemApi,
    private val service: String,
    private val group: String,
    private val context: CoroutineContext,
) {
    fun read(
        account: String,
        limit: Int,
    ): KeychainPayload? = copy(account, limit, includeData = true)

    private fun copy(
        account: String,
        limit: Int,
        includeData: Boolean,
    ): KeychainPayload? =
        nativeDictionary { query ->
            identity(query, account)
            // Do not filter away a synced, shared-group or misprotected conflicting fixed item.
            CFDictionarySetValue(query, kSecAttrSynchronizable, kSecAttrSynchronizableAny)
            CFDictionarySetValue(query, kSecReturnAttributes, kCFBooleanTrue)
            if (includeData) CFDictionarySetValue(query, kSecReturnData, kCFBooleanTrue)
            memScoped {
                val maximum = alloc<IntVar>().also { it.value = 2 }
                val number =
                    CFNumberCreate(null, kCFNumberIntType, maximum.ptr)
                        ?: temporary(InstallationTemporaryFailure.IO_FAILURE)
                try {
                    CFDictionarySetValue(query, kSecMatchLimit, number)
                } finally {
                    CFRelease(number)
                }
                val result = alloc<CFTypeRefVar>().also { it.value = null }
                try {
                    context.ensureActive()
                    val status = api.copyMatching(query, result.ptr)
                    context.ensureActive()
                    when {
                        status == errSecItemNotFound -> null
                        status == errSecDecode && includeData -> {
                            // A payload error alone cannot authorize deletion of an unverified group/protection.
                            if (copy(account, limit, includeData = false) == null) {
                                temporary(InstallationTemporaryFailure.UNCERTAIN)
                            }
                            KeychainPayload.Corrupt
                        }
                        status != errSecSuccess -> statusFailure(status)
                        else -> payload(result.value, account, limit, includeData)
                    }
                } finally {
                    result.value?.let { CFRelease(it) }
                }
            }
        }

    fun add(
        account: String,
        bytes: ByteArray,
    ): Int =
        nativeDictionary { attributes ->
            identity(attributes, account)
            protection(attributes)
            // Omit access-group only for Add: the OS chooses default; subsequent broad read must match group.
            putBytes(attributes, bytes)
            context.ensureActive()
            api.add(attributes).also { context.ensureActive() }
        }

    fun update(
        account: String,
        bytes: ByteArray,
    ): Int =
        mutationQuery(account) { query ->
            nativeDictionary { attributes ->
                putBytes(attributes, bytes)
                context.ensureActive()
                api.update(query, attributes).also { context.ensureActive() }
            }
        }

    fun delete(account: String): Int =
        mutationQuery(account) { query ->
            context.ensureActive()
            api.delete(query).also { context.ensureActive() }
        }

    private fun <T> mutationQuery(
        account: String,
        operation: (CFDictionaryRef) -> T,
    ): T =
        nativeDictionary { query ->
            identity(query, account)
            protection(query)
            putString(query, kSecAttrAccessGroup, group)
            operation(query)
        }

    private fun identity(
        query: CFMutableDictionaryRef,
        account: String,
    ) {
        CFDictionarySetValue(query, kSecClass, kSecClassGenericPassword)
        putString(query, kSecAttrService, service)
        putString(query, kSecAttrAccount, account)
        val authentication = LAContext().also { it.interactionNotAllowed = true }
        val reference = CFBridgingRetain(authentication) ?: temporary(InstallationTemporaryFailure.IO_FAILURE)
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

    private fun payload(
        raw: CFTypeRef?,
        account: String,
        limit: Int,
        includeData: Boolean,
    ): KeychainPayload {
        if (raw == null || CFGetTypeID(raw) != CFArrayGetTypeID()) permanent(InstallationPermanentFailure.STATE_CHANGED)
        val array: CFArrayRef = raw.reinterpret()
        // CFNumber(2) bounds the native result count; never accept the first of multiple matches.
        if (CFArrayGetCount(array) != 1L) permanent(InstallationPermanentFailure.STATE_CHANGED)
        val item = CFArrayGetValueAtIndex(array, 0) ?: permanent(InstallationPermanentFailure.STATE_CHANGED)
        if (CFGetTypeID(item) != CFDictionaryGetTypeID()) permanent(InstallationPermanentFailure.STATE_CHANGED)
        val attributes: CFDictionaryRef = item.reinterpret()
        requireAttributes(attributes, account)
        val value = if (includeData) CFDictionaryGetValue(attributes, kSecValueData) else null
        if (value == null || CFGetTypeID(value) != CFDataGetTypeID()) return KeychainPayload.Corrupt
        val data: CFDataRef = value.reinterpret()
        return boundedDataPayload(data, limit)
    }

    private fun requireAttributes(
        attributes: CFDictionaryRef,
        account: String,
    ) {
        val itemClass = CFDictionaryGetValue(attributes, kSecClass)
        val accessible = CFDictionaryGetValue(attributes, kSecAttrAccessible)
        val sync = CFDictionaryGetValue(attributes, kSecAttrSynchronizable)
        val expected =
            stringMatches(attributes, kSecAttrService, service) &&
                stringMatches(attributes, kSecAttrAccount, account) &&
                stringMatches(attributes, kSecAttrAccessGroup, group) &&
                (itemClass == null || keychainCfStringEquals(itemClass, kSecClassGenericPassword)) &&
                accessible != null &&
                CFGetTypeID(accessible) == CFStringGetTypeID() &&
                keychainCfStringEquals(accessible, kSecAttrAccessibleWhenUnlockedThisDeviceOnly) &&
                sync != null &&
                CFGetTypeID(sync) == CFBooleanGetTypeID() &&
                keychainCfBooleanEquals(sync, kCFBooleanFalse)
        if (!expected) permanent(InstallationPermanentFailure.STATE_CHANGED)
    }
}

private fun boundedDataPayload(
    data: CFDataRef,
    limit: Int,
): KeychainPayload {
    val length = CFDataGetLength(data)
    // Security owns its returned CFData allocation; this ceiling bounds OUR allocation and decoding.
    return when {
        length > limit.toLong() -> KeychainPayload.TooLarge
        length < 0 -> KeychainPayload.Corrupt
        length == 0L -> KeychainPayload.Bytes(ByteArray(0))
        else ->
            CFDataGetBytePtr(data)?.let {
                KeychainPayload.Bytes(it.reinterpret<ByteVar>().readBytes(length.toInt()))
            } ?: KeychainPayload.Corrupt
    }
}

private sealed interface CredentialSlot {
    data object Absent : CredentialSlot

    class Readable(
        val record: InstallationCredentialRecord,
    ) : CredentialSlot {
        override fun toString(): String = "CredentialSlot.Readable(redacted)"
    }

    class Unreadable(
        val reason: InstallationPermanentFailure,
    ) : CredentialSlot
}

private sealed interface KeychainPayload {
    class Bytes(
        val bytes: ByteArray,
    ) : KeychainPayload {
        override fun toString(): String = "KeychainPayload.Bytes(redacted)"
    }

    data object Corrupt : KeychainPayload

    data object TooLarge : KeychainPayload
}

private object KeychainPolicy {
    private const val MAX_ACCESS_GROUP_LENGTH = 255

    fun requireIos(record: InstallationCredentialRecord) {
        if (record.material.platform != InstallationPlatform.IOS) permanent(InstallationPermanentFailure.UNSUPPORTED)
    }

    fun requireReplacement(
        old: InstallationCredentialRecord,
        next: InstallationCredentialRecord,
    ) {
        if (old.state != InstallationCredentialState.ACTIVE || next.state == InstallationCredentialState.ACTIVE) {
            permanent(InstallationPermanentFailure.STATE_CHANGED)
        }
        if (!old.material.sameAs(next.material) || old.credentialVersion != next.credentialVersion) {
            permanent(InstallationPermanentFailure.STATE_CHANGED)
        }
        if (old.localGeneration == Long.MAX_VALUE || next.localGeneration != old.localGeneration + 1) {
            permanent(InstallationPermanentFailure.STATE_CHANGED)
        }
    }

    fun requireMarker(
        actual: CredentialCleanupMarker,
        expected: CredentialCleanupMarker,
    ) {
        if (!actual.sameAs(expected)) permanent(InstallationPermanentFailure.MARKER_CONFLICT)
    }

    fun matchesCleanup(
        record: InstallationCredentialRecord,
        marker: CredentialCleanupMarker,
    ): Boolean =
        record.localGeneration == marker.expectedGeneration &&
            when (marker.reason) {
                CredentialCleanupReason.USER_RESET_CONFIRMED ->
                    record.state == InstallationCredentialState.LOCAL_RESET_PENDING
                CredentialCleanupReason.SERVER_TERMINAL_CONFIRMED,
                CredentialCleanupReason.REMOTE_DELETE_ABANDON_CONFIRMED,
                -> record.state == InstallationCredentialState.DELETION_PENDING
                CredentialCleanupReason.UNREADABLE_RESET_CONFIRMED -> false
            }

    fun validAccessGroup(value: String): Boolean =
        value.length in 1..MAX_ACCESS_GROUP_LENGTH &&
            value.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it == '.' || it == '-' } &&
            value.split('.').all { it.isNotEmpty() }
}

private fun <T> decode(
    payload: KeychainPayload,
    codec: (ByteArray) -> InstallationCodecResult<T>,
): InstallationCodecResult<T> =
    when (payload) {
        is KeychainPayload.Bytes ->
            try {
                codec(payload.bytes)
            } finally {
                // Best effort only: decoded immutable strings, codec copies and native buffers
                // are not zeroization claims.
                payload.bytes.fill(0)
            }
        KeychainPayload.Corrupt -> InstallationCodecResult.Corrupt
        KeychainPayload.TooLarge -> InstallationCodecResult.TooLarge
    }

private fun encoded(
    value: InstallationCodecResult<InstallationEncodedPayload>,
    operation: (ByteArray) -> Int,
): Int {
    val payload =
        when (value) {
            is InstallationCodecResult.Value -> value.value
            InstallationCodecResult.Corrupt -> permanent(InstallationPermanentFailure.CORRUPT)
            InstallationCodecResult.TooLarge -> permanent(InstallationPermanentFailure.TOO_LARGE)
        }
    val bytes = payload.copyBytes()
    return try {
        operation(bytes)
    } finally {
        bytes.fill(0)
    }
}

private inline fun <T> nativeDictionary(operation: (CFMutableDictionaryRef) -> T): T {
    val dictionary =
        CFDictionaryCreateMutable(
            null,
            0,
            kCFTypeDictionaryKeyCallBacks.ptr,
            kCFTypeDictionaryValueCallBacks.ptr,
        ) ?: temporary(InstallationTemporaryFailure.IO_FAILURE)
    return try {
        operation(dictionary)
    } finally {
        CFRelease(dictionary)
    }
}

private fun putString(
    dictionary: CFMutableDictionaryRef,
    key: CFStringRef?,
    value: String,
) {
    val string =
        CFStringCreateWithCString(null, value, kCFStringEncodingUTF8)
            ?: temporary(InstallationTemporaryFailure.IO_FAILURE)
    try {
        CFDictionarySetValue(dictionary, key, string)
    } finally {
        CFRelease(string)
    }
}

private fun stringMatches(
    dictionary: CFDictionaryRef,
    key: CFStringRef?,
    expected: String,
): Boolean {
    val actual = CFDictionaryGetValue(dictionary, key)
    if (actual == null || CFGetTypeID(actual) != CFStringGetTypeID()) return false
    val string =
        CFStringCreateWithCString(null, expected, kCFStringEncodingUTF8)
            ?: temporary(InstallationTemporaryFailure.IO_FAILURE)
    return try {
        keychainCfStringEquals(actual, string)
    } finally {
        CFRelease(string)
    }
}

private fun putBytes(
    dictionary: CFMutableDictionaryRef,
    bytes: ByteArray,
) {
    val data =
        bytes.usePinned { pinned -> CFDataCreate(null, pinned.addressOf(0).reinterpret(), bytes.size.toLong()) }
            ?: temporary(InstallationTemporaryFailure.IO_FAILURE)
    try {
        CFDictionarySetValue(dictionary, kSecValueData, data)
    } finally {
        CFRelease(data)
    }
}

private class KeychainRefusal(
    val failure: InstallationStorageFailure,
) : Exception("Installation storage operation refused")

private fun permanent(reason: InstallationPermanentFailure): Nothing =
    throw KeychainRefusal(InstallationStorageFailure.PermanentFailure(reason))

private fun temporary(reason: InstallationTemporaryFailure): Nothing =
    throw KeychainRefusal(InstallationStorageFailure.TemporarilyUnavailable(reason))

private fun statusFailure(status: Int): Nothing =
    when (status) {
        errSecInteractionNotAllowed, errSecInteractionRequired -> temporary(InstallationTemporaryFailure.LOCKED)
        errSecIO, errSecNotAvailable -> temporary(InstallationTemporaryFailure.IO_FAILURE)
        errSecDecode -> permanent(InstallationPermanentFailure.CORRUPT)
        errSecUnimplemented, errSecMissingEntitlement, errSecParam ->
            permanent(InstallationPermanentFailure.UNSUPPORTED)
        else -> temporary(InstallationTemporaryFailure.UNCERTAIN)
    }
