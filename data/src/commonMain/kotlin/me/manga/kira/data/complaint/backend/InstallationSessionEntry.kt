package me.manga.kira.data.complaint.backend

import me.manga.kira.platform.storage.InstallationCredentialRecord
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeMark

/** A memory-only token identity, not the pending observation or authority of any caller using it. */
internal class InstallationSessionEntry(
    private val record: InstallationCredentialRecord,
    private val issuer: ReconciliationIssuer,
    val response: ComplaintSessionResponse,
    private val started: TimeMark,
) {
    fun matches(
        expected: InstallationCredentialRecord,
        expectedIssuer: ReconciliationIssuer,
    ): Boolean = issuer === expectedIssuer && record.sameAs(expected)

    /** Rebinding an observation never restarts this mark or extends the authenticated token lifetime. */
    fun isFresh(): Boolean {
        val elapsed = started.elapsedNow()
        return elapsed >= Duration.ZERO && elapsed < response.expiresInSeconds.seconds
    }

    override fun toString(): String = "InstallationSessionEntry(redacted)"
}
