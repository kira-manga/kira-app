package me.manga.kira.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import me.manga.kira.data.local.entity.ReaderLegacyCleanupEntity
import me.manga.kira.data.local.entity.ReaderLegacyCleanupState

/**
 * Durable transfer tombstones only. No Settings reads/removals, owner inference or copy policy.
 * Transfer must inspect [find] before copying and record its decision in the same Room writer.
 */
@Dao
abstract class ReaderLegacyCleanupDao {
    /** Exact full-payload identity; a hash/key match alone is never sufficient. */
    @Query("SELECT * FROM reader_legacy_cleanup WHERE legacyKey = :legacyKey AND capturedPayload = :capturedPayload")
    abstract suspend fun find(legacyKey: String, capturedPayload: String): ReaderLegacyCleanupEntity?

    /** Includes ACKED and CONFLICT: process/store reopening must not silently recopy old payloads. */
    @Query("SELECT * FROM reader_legacy_cleanup ORDER BY legacyKey, capturedPayload")
    abstract suspend fun allReceipts(): List<ReaderLegacyCleanupEntity>

    /** Never replaces a previous destination/epoch/decision; conflicting ownership aborts the writer. */
    @Transaction
    open suspend fun recordOnce(receipt: ReaderLegacyCleanupEntity): ReaderLegacyCleanupEntity {
        require(receipt.state == ReaderLegacyCleanupState.PENDING)
        find(receipt.legacyKey, receipt.capturedPayload)?.let { existing ->
            check(existing.chapterId == receipt.chapterId && existing.capture == receipt.capture) {
                "Legacy payload already has a different transfer decision"
            }
            return existing
        }
        check(insertReceipt(receipt) > 0) { "Legacy cleanup receipt was not inserted" }
        return checkNotNull(find(receipt.legacyKey, receipt.capturedPayload)).also { check(it == receipt) }
    }

    /** CAS of cleanup observation only; false is stale/absent. PENDING cannot be restored. */
    suspend fun markCleanup(
        legacyKey: String,
        capturedPayload: String,
        expected: ReaderLegacyCleanupState,
        observed: ReaderLegacyCleanupState,
    ): Boolean {
        require(observed != ReaderLegacyCleanupState.PENDING)
        val changed = updateState(legacyKey, capturedPayload, expected, observed)
        check(changed in 0..1) { "Unexpected legacy cleanup update count" }
        return changed == 1
    }

    @Insert(onConflict = OnConflictStrategy.ABORT)
    protected abstract suspend fun insertReceipt(receipt: ReaderLegacyCleanupEntity): Long

    @Query(
        """
        UPDATE reader_legacy_cleanup SET state = :observed
        WHERE legacyKey = :legacyKey AND capturedPayload = :capturedPayload AND state = :expected
        """,
    )
    protected abstract suspend fun updateState(
        legacyKey: String,
        capturedPayload: String,
        expected: ReaderLegacyCleanupState,
        observed: ReaderLegacyCleanupState,
    ): Int
}
