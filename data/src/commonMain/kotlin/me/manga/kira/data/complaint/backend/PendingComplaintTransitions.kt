package me.manga.kira.data.complaint.backend

import me.manga.kira.platform.storage.InstallationCredentialRecord
import me.manga.kira.platform.storage.InstallationCredentialState
import me.manga.kira.platform.storage.PendingComplaintSlot
import me.manga.kira.platform.storage.PendingComplaintSnapshot
import kotlin.time.Instant

internal enum class PendingComplaintHold {
    CORRUPT_SLOT,
    TOO_LARGE,
    STALE_BINDING,
    INACTIVE_CREDENTIAL,
    NOT_PREPARED,
    REQUEST_MISMATCH,
    INVALID_TIME,
    DISPATCH_UNCERTAIN,
}

/** Exact opaque-slot proposals, NOT executed mutations, read-back facts or permission to construct/send/retry HTTP. */
internal sealed interface PendingComplaintChange {
    class CreateIfMissing(
        val replacement: PendingComplaintSlot,
    ) : PendingComplaintChange

    class Replace(
        val expected: PendingComplaintSlot,
        val replacement: PendingComplaintSlot,
    ) : PendingComplaintChange

    class Delete(
        val expected: PendingComplaintSlot,
    ) : PendingComplaintChange

    class Keep(
        val reason: PendingComplaintHold,
    ) : PendingComplaintChange
}

/** Decoded metadata does not mean reconciled, empty physical storage or new-mutation admission. */
internal sealed interface PendingComplaintInventory {
    class Decoded(
        records: List<PendingComplaintRecord>,
    ) : PendingComplaintInventory {
        private val content = records.toList()

        fun entries(): List<PendingComplaintRecord> = content.toList()

        override fun toString(): String = "PendingComplaintInventory.Decoded(redacted)"
    }

    class Quarantined(
        val reason: PendingComplaintHold,
    ) : PendingComplaintInventory
}

/**
 * Pure comparison/state policy. The supplied credential record is not a fresh durable read;
 * supplied fingerprint/time are not recomputed/authenticated. No request, status or admission
 * producer exists here. A future caller must keep the existing coordinator mutex, recheck its
 * real durable credential, execute exact CAS and independently read back before any request byte.
 * Existing coordinator refusal of all nonempty pending work is deliberately unchanged.
 */
internal object PendingComplaintTransitions {
    fun prepare(
        record: PendingComplaintRecord,
        current: InstallationCredentialRecord,
    ): PendingComplaintChange {
        val hold = holdFor(record, current)
        return when {
            hold != null -> PendingComplaintChange.Keep(hold)
            record.state != PendingComplaintState.PREPARED ->
                PendingComplaintChange.Keep(PendingComplaintHold.NOT_PREPARED)
            else -> encoded(record) { PendingComplaintChange.CreateIfMissing(it) }
        }
    }

    fun rebasePrepared(
        expected: PendingComplaintSlot,
        current: InstallationCredentialRecord,
        suppliedSessionIssuedAt: Instant,
    ): PendingComplaintChange =
        withRecord(expected, current) { record ->
            if (record.state != PendingComplaintState.PREPARED) {
                PendingComplaintChange.Keep(PendingComplaintHold.NOT_PREPARED)
            } else {
                val next = record.rebasedPrepared(suppliedSessionIssuedAt)
                if (next == null) {
                    PendingComplaintChange.Keep(PendingComplaintHold.INVALID_TIME)
                } else {
                    encoded(next) { PendingComplaintChange.Replace(expected, it) }
                }
            }
        }

    /** Matching asserted metadata is necessary but cannot establish that a live request produced this fingerprint. */
    fun markMayHaveDispatched(
        expected: PendingComplaintSlot,
        current: InstallationCredentialRecord,
        matchingRequest: PendingComplaintRequest,
    ): PendingComplaintChange =
        withRecord(expected, current) { record ->
            if (!record.request.sameAs(matchingRequest)) {
                PendingComplaintChange.Keep(PendingComplaintHold.REQUEST_MISMATCH)
            } else {
                val next = record.markedDispatched()
                if (next == null) {
                    PendingComplaintChange.Keep(PendingComplaintHold.NOT_PREPARED)
                } else {
                    encoded(next) { PendingComplaintChange.Replace(expected, it) }
                }
            }
        }

    /** No direct response, age, timeout, cancellation or caller-supplied terminal enum clears dispatched work. */
    fun cancelPrepared(
        expected: PendingComplaintSlot,
        current: InstallationCredentialRecord,
    ): PendingComplaintChange =
        withRecord(expected, current) { record ->
            if (record.state == PendingComplaintState.PREPARED) {
                PendingComplaintChange.Delete(expected)
            } else {
                PendingComplaintChange.Keep(PendingComplaintHold.DISPATCH_UNCERTAIN)
            }
        }

    fun inspect(
        snapshot: PendingComplaintSnapshot,
        current: InstallationCredentialRecord,
    ): PendingComplaintInventory {
        val records = mutableListOf<PendingComplaintRecord>()
        for (slot in snapshot.entries()) {
            val decoded = PendingComplaintRecordCodec.decode(slot)
            val hold =
                when (decoded) {
                    is PendingComplaintCodecResult.Value -> holdFor(decoded.value, current)
                    PendingComplaintCodecResult.Corrupt -> PendingComplaintHold.CORRUPT_SLOT
                    PendingComplaintCodecResult.TooLarge -> PendingComplaintHold.TOO_LARGE
                }
            if (hold != null) return PendingComplaintInventory.Quarantined(hold)
            if (decoded is PendingComplaintCodecResult.Value) records += decoded.value
        }
        return PendingComplaintInventory.Decoded(records)
    }

    private fun holdFor(
        record: PendingComplaintRecord,
        current: InstallationCredentialRecord,
    ): PendingComplaintHold? =
        when {
            current.state != InstallationCredentialState.ACTIVE -> PendingComplaintHold.INACTIVE_CREDENTIAL
            !record.binding.matches(current) -> PendingComplaintHold.STALE_BINDING
            else -> null
        }

    private inline fun withRecord(
        expected: PendingComplaintSlot,
        current: InstallationCredentialRecord,
        action: (PendingComplaintRecord) -> PendingComplaintChange,
    ): PendingComplaintChange =
        when (val decoded = PendingComplaintRecordCodec.decode(expected)) {
            is PendingComplaintCodecResult.Value -> {
                val hold = holdFor(decoded.value, current)
                if (hold != null) PendingComplaintChange.Keep(hold) else action(decoded.value)
            }
            PendingComplaintCodecResult.Corrupt -> PendingComplaintChange.Keep(PendingComplaintHold.CORRUPT_SLOT)
            PendingComplaintCodecResult.TooLarge -> PendingComplaintChange.Keep(PendingComplaintHold.TOO_LARGE)
        }

    private inline fun encoded(
        record: PendingComplaintRecord,
        action: (PendingComplaintSlot) -> PendingComplaintChange,
    ): PendingComplaintChange =
        when (val encoded = PendingComplaintRecordCodec.encode(record)) {
            is PendingComplaintCodecResult.Value -> action(encoded.value)
            PendingComplaintCodecResult.Corrupt -> PendingComplaintChange.Keep(PendingComplaintHold.CORRUPT_SLOT)
            PendingComplaintCodecResult.TooLarge -> PendingComplaintChange.Keep(PendingComplaintHold.TOO_LARGE)
        }
}
