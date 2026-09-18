package me.manga.kira.di

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import me.manga.kira.platform.storage.InstallationValueResult
import me.manga.kira.platform.storage.PendingClearResult
import me.manga.kira.platform.storage.PendingComplaintActionStore
import me.manga.kira.platform.storage.PendingComplaintSlot
import me.manga.kira.platform.storage.PendingComplaintSnapshot
import me.manga.kira.platform.storage.PendingCreateResult
import me.manga.kira.platform.storage.PendingDeleteResult
import me.manga.kira.platform.storage.PendingReadResult
import me.manga.kira.platform.storage.PendingReplaceResult
import kotlin.test.assertIs

/** Exact in-memory CAS test store; no platform persistence implementation or schema is changed. */
internal class MobileReplyGraphPending : PendingComplaintActionStore {
    val slots = mutableListOf<PendingComplaintSlot>()
    val transitions = mutableListOf<JsonObject>()
    var deletes = 0
        private set

    override suspend fun read(): PendingReadResult =
        PendingReadResult.Verified(
            assertIs<InstallationValueResult.Valid<PendingComplaintSnapshot>>(
                PendingComplaintSnapshot.checked(slots),
            ).value,
        )

    override suspend fun createIfMissing(slot: PendingComplaintSlot): PendingCreateResult {
        if (slots.any { it.id == slot.id }) return PendingCreateResult.AlreadyPresent
        slots += slot
        remember(slot)
        return PendingCreateResult.Stored
    }

    override suspend fun replace(
        expected: PendingComplaintSlot,
        replacement: PendingComplaintSlot,
    ): PendingReplaceResult {
        val index = slots.indexOfFirst { it.id == expected.id }
        if (index < 0) return PendingReplaceResult.Missing
        if (!slots[index].sameAs(expected) || replacement.id != expected.id) return PendingReplaceResult.Stale
        slots[index] = replacement
        remember(replacement)
        return PendingReplaceResult.Stored
    }

    override suspend fun delete(expected: PendingComplaintSlot): PendingDeleteResult {
        val index = slots.indexOfFirst { it.id == expected.id }
        if (index < 0) return PendingDeleteResult.Missing
        if (!slots[index].sameAs(expected)) return PendingDeleteResult.Stale
        slots.removeAt(index)
        deletes++
        return PendingDeleteResult.Deleted
    }

    override suspend fun clearForConfirmedRecovery(): PendingClearResult =
        error("reply must not clear installation state")

    private fun remember(slot: PendingComplaintSlot) {
        transitions += assertIs<JsonObject>(Json.parseToJsonElement(slot.bytes().decodeToString()))
    }
}
