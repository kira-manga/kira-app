package me.manga.kira.data.complaint.backend

import me.manga.kira.platform.storage.InstallationCredentialRecord
import kotlin.time.Instant

/** Four public comparison fields only; no credential material or freshness/authentication claim. */
internal class PendingComplaintBinding private constructor(
    val installationId: String,
    val credentialVersion: Long,
    val localGeneration: Long,
    val dataScopeId: String,
) {
    fun matches(record: InstallationCredentialRecord): Boolean =
        installationId == record.material.installationId &&
            credentialVersion == record.credentialVersion &&
            localGeneration == record.localGeneration &&
            dataScopeId == record.material.dataScopeId

    override fun toString(): String = "PendingComplaintBinding(redacted)"

    companion object {
        fun checked(
            installationId: String,
            credentialVersion: Long,
            localGeneration: Long,
            dataScopeId: String,
        ): PendingComplaintBinding? =
            when {
                !pendingV4(installationId) -> null
                credentialVersion <= 0 || localGeneration <= 0 -> null
                dataScopeId != LIVE_SCOPE && !pendingV4(dataScopeId) -> null
                else -> PendingComplaintBinding(installationId, credentialVersion, localGeneration, dataScopeId)
            }
    }
}

/** Local persisted operation vocabulary, not an installed backend wire-operation mapping. */
internal enum class PendingComplaintOperation { CREATE_REPORT, CREATE_REPLY, EDIT_CONTENT, DELETE_OWNED }

/**
 * Closed owner tuples. Only newly allocated client IDs require v4;
 * seeded parent/target IDs need canonical spelling.
 */
internal class PendingComplaintAction private constructor(
    val operation: PendingComplaintOperation,
    val targetId: String,
    val parentId: String?,
    val expectedVersion: Long?,
) {
    fun orderedTargetIds(): List<String> = parentId?.let { listOf(it, targetId) } ?: listOf(targetId)

    fun canonicalPrecondition(): String? = expectedVersion?.let { "\"complaint-$targetId-v$it\"" }

    fun sameAs(other: PendingComplaintAction): Boolean =
        operation == other.operation &&
            targetId == other.targetId &&
            parentId == other.parentId &&
            expectedVersion == other.expectedVersion

    override fun toString(): String = "PendingComplaintAction(redacted)"

    companion object {
        fun checked(
            operation: PendingComplaintOperation,
            targetId: String,
            parentId: String?,
            expectedVersion: Long?,
        ): PendingComplaintAction? {
            val valid =
                when (operation) {
                    PendingComplaintOperation.CREATE_REPORT ->
                        pendingV4(targetId) && parentId == null && expectedVersion == null
                    PendingComplaintOperation.CREATE_REPLY ->
                        pendingV4(targetId) && parentId != null && pendingUuid(parentId) && expectedVersion == null
                    PendingComplaintOperation.EDIT_CONTENT, PendingComplaintOperation.DELETE_OWNED ->
                        pendingUuid(targetId) && parentId == null && expectedVersion != null && expectedVersion > 0
                }
            return if (valid) PendingComplaintAction(operation, targetId, parentId, expectedVersion) else null
        }
    }
}

/** Encoding validation ONLY. No normalized request frame is computed or compared with live prose here. */
internal class PendingComplaintFingerprint private constructor(
    val encoded: String,
) {
    val version: Int get() = 1

    override fun toString(): String = "PendingComplaintFingerprint(redacted)"

    companion object {
        fun checked(
            version: Int,
            encoded: String,
        ): PendingComplaintFingerprint? =
            when {
                version != 1 || encoded.length != FINGERPRINT_LENGTH -> null
                encoded.any { it !in BASE64URL } -> null
                BASE64URL.indexOf(encoded.last()) % CANONICAL_TAIL_DIVISOR != 0 -> null
                else -> PendingComplaintFingerprint(encoded)
            }
    }
}

internal class PendingComplaintRequest private constructor(
    val action: PendingComplaintAction,
    val key: String,
    val fingerprint: PendingComplaintFingerprint,
) {
    fun sameAs(other: PendingComplaintRequest): Boolean =
        action.sameAs(other.action) &&
            key == other.key &&
            fingerprint.version == other.fingerprint.version &&
            fingerprint.encoded == other.fingerprint.encoded

    override fun toString(): String = "PendingComplaintRequest(redacted)"

    companion object {
        fun checked(
            action: PendingComplaintAction,
            key: String,
            fingerprint: PendingComplaintFingerprint,
        ): PendingComplaintRequest? = if (pendingV4(key)) PendingComplaintRequest(action, key, fingerprint) else null
    }
}

/** Supplied session time is NOT authenticated here. Creation time is display-only, never a receipt clock. */
internal class PendingComplaintTimes private constructor(
    val createdAt: Instant,
    val sessionIssuedAt: Instant,
    val serverReceiptSafeUntil: Instant,
) {
    override fun toString(): String = "PendingComplaintTimes(redacted)"

    companion object {
        const val RECEIPT_WINDOW_SECONDS: Long = 604_800

        fun checked(
            createdAt: Instant,
            suppliedSessionIssuedAt: Instant,
        ): PendingComplaintTimes? {
            val seconds = suppliedSessionIssuedAt.epochSeconds
            if (seconds > Long.MAX_VALUE - RECEIPT_WINDOW_SECONDS) return null
            return pendingInstant(seconds + RECEIPT_WINDOW_SECONDS, suppliedSessionIssuedAt.nanosecondsOfSecond)
                ?.let { PendingComplaintTimes(createdAt, suppliedSessionIssuedAt, it) }
        }
    }
}

internal enum class PendingComplaintState { PREPARED, MAY_HAVE_DISPATCHED }

/**
 * Immutable no-prose metadata. Decoding or constructing this record proves
 * neither durable storage nor dispatch/retry authority.
 */
internal class PendingComplaintRecord private constructor(
    val binding: PendingComplaintBinding,
    val request: PendingComplaintRequest,
    val times: PendingComplaintTimes,
    val state: PendingComplaintState,
) {
    val schemaVersion: Int get() = 1

    internal fun rebasedPrepared(suppliedSessionIssuedAt: Instant): PendingComplaintRecord? {
        if (state != PendingComplaintState.PREPARED) return null
        return PendingComplaintTimes
            .checked(times.createdAt, suppliedSessionIssuedAt)
            ?.let { PendingComplaintRecord(binding, request, it, state) }
    }

    internal fun markedDispatched(): PendingComplaintRecord? =
        if (state == PendingComplaintState.PREPARED) {
            PendingComplaintRecord(binding, request, times, PendingComplaintState.MAY_HAVE_DISPATCHED)
        } else {
            null
        }

    override fun toString(): String = "PendingComplaintRecord(redacted)"

    companion object {
        fun prepared(
            binding: PendingComplaintBinding,
            request: PendingComplaintRequest,
            times: PendingComplaintTimes,
        ): PendingComplaintRecord = PendingComplaintRecord(binding, request, times, PendingComplaintState.PREPARED)

        /** Structural restoration, not a lifecycle transition or proof that these bytes ever reached storage. */
        internal fun restored(
            schema: Int,
            binding: PendingComplaintBinding,
            request: PendingComplaintRequest,
            times: PendingComplaintTimes,
            state: PendingComplaintState,
        ): PendingComplaintRecord? = if (schema == 1) PendingComplaintRecord(binding, request, times, state) else null
    }
}

/** Reject invalid nanos and saturating/out-of-range Instant conversions rather than normalizing them. */
internal fun pendingInstant(
    seconds: Long,
    nanos: Int,
): Instant? {
    if (nanos !in 0..MAX_NANOS) return null
    return try {
        Instant
            .fromEpochSeconds(seconds, nanos.toLong())
            .takeIf { it.epochSeconds == seconds && it.nanosecondsOfSecond == nanos }
    } catch (_: IllegalArgumentException) {
        null
    }
}

private fun pendingUuid(value: String): Boolean = value.length == UUID_LENGTH && CANONICAL_UUID.matches(value)

private fun pendingV4(value: String): Boolean =
    pendingUuid(value) && value[UUID_VERSION_OFFSET] == '4' && value[UUID_VARIANT_OFFSET] in "89ab"

private const val UUID_LENGTH = 36
private const val UUID_VERSION_OFFSET = 14
private const val UUID_VARIANT_OFFSET = 19
private const val FINGERPRINT_LENGTH = 43
private const val CANONICAL_TAIL_DIVISOR = 4
private const val MAX_NANOS = 999_999_999
private const val LIVE_SCOPE = "00000000-0000-0000-0000-000000000000"
private const val BASE64URL = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"
private val CANONICAL_UUID = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
