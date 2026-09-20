package me.manga.kira.data.repository.progress

import com.russhwolf.settings.ObservableSettings
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import me.manga.kira.domain.model.progress.LegacyProgressCleanup

/**
 * One composition-owned gate shared by every remaining legacy progress accessor in this process.
 * It is not Settings CAS or cross-process exclusion. Blocks must not acquire Room or readiness;
 * capture -> release -> Room commit -> cleanup -> release -> Room acknowledgement is the lock order.
 */
class LegacyProgressSettingsGate {
    private val mutex = Mutex()

    /** Serializes Settings access; composition must inject this same instance, not one per caller. */
    suspend fun <T> withAccess(block: suspend () -> T): T = mutex.withLock { block() }
}

/**
 * Composition must prove all old writers stopped/migrated/quiesced, holding that ownership for the
 * ENTIRE callback. Null denies the callback. Merely taking the Settings gate is not such proof:
 * the old shipping URL-only repository is ungated until caller cutover. No default grant exists.
 * Acquired under the shared Settings gate; never acquire Room here. External/process writers need
 * separate exclusion before composition may grant ownership; this interface does not provide it.
 * Establish cutover/quiescence before invoking cleanup: an authority must deny, not wait on any
 * writer that needs this gate. It may not turn the gate -> ownership order into a lock cycle.
 */
interface LegacyProgressWriterOwnership {
    suspend fun <T : Any> withExclusiveWriterOwnership(block: () -> T): T?
}

/**
 * Uses the ordinary ObservableSettings("kira_settings") store, NOT secure storage. Exact removal
 * requires both the shared gate and an explicit writer lease. A returned remove/absence observation
 * is not a physical-flush acknowledgement; receipts must survive and be reconciled on reopening.
 */
class LegacyProgressSettings(
    private val settings: ObservableSettings,
    private val gate: LegacyProgressSettingsGate,
    private val ownership: LegacyProgressWriterOwnership,
) {
    internal suspend fun capture(chapterUrl: String): CapturedLegacyProgress? = gate.withAccess {
        val key = legacyProgressKey(chapterUrl)
        settings.getStringOrNull(key)?.let { CapturedLegacyProgress(key, it) }
    }

    internal suspend fun cleanup(captured: CapturedLegacyProgress): LegacyProgressCleanup = gate.withAccess {
        observe(captured) ?: ownership.withExclusiveWriterOwnership {
            // Re-read AFTER obtaining ownership: an ungated old writer may have changed the cell
            // while that lease was being obtained. Hold the lease through compare AND remove.
            observe(captured) ?: removeOwned(captured)
        } ?: LegacyProgressCleanup.OWNERSHIP_UNAVAILABLE
    }

    private fun observe(captured: CapturedLegacyProgress): LegacyProgressCleanup? {
        val current = settings.getStringOrNull(captured.key)
        return when {
            current == null -> LegacyProgressCleanup.ACKNOWLEDGED
            current != captured.payload -> LegacyProgressCleanup.CONFLICT
            else -> null
        }
    }

    private fun removeOwned(captured: CapturedLegacyProgress): LegacyProgressCleanup {
        settings.remove(captured.key)
        return LegacyProgressCleanup.ACKNOWLEDGED
    }
}
