package me.manga.kira.data.complaint.backend

import me.manga.kira.data.complaint.backend.InstallationCredentialCoordination.ReconciliationPermit
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeMark

/** An exact cache instance and binding, not authority for an unregistered HTTP request. */
internal class ComplaintHistorySession(
    val permit: ReconciliationPermit,
    val response: ComplaintSessionResponse,
    private val started: TimeMark,
) {
    fun isFresh(): Boolean {
        val elapsed = started.elapsedNow()
        return elapsed >= Duration.ZERO && elapsed < response.expiresInSeconds.seconds
    }

    override fun toString(): String = "ComplaintHistorySession(redacted)"
}

internal sealed interface ComplaintHistorySessionResult {
    class Ready(val session: ComplaintHistorySession) : ComplaintHistorySessionResult
    class Failed(val failure: ComplaintSessionResult) : ComplaintHistorySessionResult
}

/** Captured before the first session attempt; Missing is an exact empty-state observation, not a failed read. */
internal sealed interface ComplaintHistoryAdmission {
    class Existing(val permit: ReconciliationPermit) : ComplaintHistoryAdmission {
        override fun toString(): String = "ComplaintHistoryAdmission.Existing(redacted)"
    }

    class Missing(val issuer: ReconciliationIssuer) : ComplaintHistoryAdmission {
        override fun toString(): String = "ComplaintHistoryAdmission.Missing(redacted)"
    }
}
