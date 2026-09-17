package me.manga.kira.data.complaint.backend

import me.manga.kira.platform.storage.InstallationCredentialRecord
import kotlin.time.Duration
import kotlin.time.TimeSource

/** Memory-only 202 delay, not new durable protocol metadata or an automatic request loop. */
internal class InstallationDeletionRetry(
    private val record: InstallationCredentialRecord,
    private val seconds: Int,
    clock: TimeSource,
) {
    private val started = clock.markNow()

    fun remaining(binding: InstallationDeletionBinding): Int? {
        if (!record.sameAs(binding.record)) return null
        val elapsed = started.elapsedNow()
        if (elapsed < Duration.ZERO) return seconds
        val remainingMillis = seconds * MILLIS_PER_SECOND - elapsed.inWholeMilliseconds
        if (remainingMillis <= 0) return null
        return ((remainingMillis + MILLIS_PER_SECOND - 1) / MILLIS_PER_SECOND).toInt()
    }

    override fun toString(): String = "InstallationDeletionRetry(redacted)"

    private companion object {
        const val MILLIS_PER_SECOND = 1_000L
    }
}
