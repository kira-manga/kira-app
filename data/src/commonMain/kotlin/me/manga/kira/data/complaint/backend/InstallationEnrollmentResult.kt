package me.manga.kira.data.complaint.backend

import me.manga.kira.data.complaint.backend.InstallationCredentialCoordination.Outcome

/** Enrollment-only values. Success is current at publication, not authority for a later mutation or cleanup. */
internal sealed interface InstallationEnrollmentResult<out T> {
    class Ready<T>(
        val value: T,
    ) : InstallationEnrollmentResult<T> {
        override fun toString(): String = "InstallationEnrollment.Ready(redacted)"
    }

    sealed interface Failure : InstallationEnrollmentResult<Nothing>

    /** Reuses the existing content-free installation transport categories; never retains an exception. */
    data class Failed(
        val reason: ComplaintSessionFailure,
    ) : Failure

    /** Bounded status only. No problem body is interpreted as cleanup or replacement authority. */
    data class HttpFailure(
        val status: Int,
    ) : Failure

    data class MaterialFailure(
        val reason: InstallationEnrollmentMaterialFailure,
    ) : Failure

    class LocalFailure(
        val outcome: Outcome<Nothing>,
    ) : Failure {
        override fun toString(): String = "InstallationEnrollment.LocalFailure(redacted)"
    }
}

internal enum class InstallationEnrollmentMaterialFailure { INVALID_SCOPE, ENTROPY, UNSUPPORTED }

/** Content-free conversion after the coordinator's existing serialized operation has returned. */
internal fun Outcome<InstallationEnrollmentResult<Unit>>.enrollmentResult(): InstallationEnrollmentResult<Unit> =
    when (this) {
        is Outcome.Success -> value
        is Outcome.Refused -> InstallationEnrollmentResult.LocalFailure(this)
        is Outcome.StorageFailure -> InstallationEnrollmentResult.LocalFailure(this)
        is Outcome.Invalid -> InstallationEnrollmentResult.LocalFailure(this)
    }
