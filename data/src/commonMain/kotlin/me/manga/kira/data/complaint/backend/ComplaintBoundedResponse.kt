package me.manga.kira.data.complaint.backend

import io.ktor.client.statement.HttpResponse
import io.ktor.http.HttpStatusCode
import me.manga.kira.platform.storage.InstallationCredentialRecord

/** Fixed installation response readers. Native receive/trust policy still requires its separate qualification. */
internal object ComplaintBoundedResponse {
    const val MAX_BYTES = 16 * 1_024
    const val CONTRACT_HEADER = "X-Kira-Complaint-Contract"

    /** Existing session route/status/decoder behavior is unchanged. */
    suspend fun read(
        response: HttpResponse,
        endpoint: ComplaintBackendEndpoint,
        expected: InstallationCredentialRecord,
    ): ComplaintSessionResult =
        when (
            val body =
                ComplaintBoundedInstallationBody.read(response, endpoint, ComplaintInstallationResponseRoute.SESSION)
        ) {
            is BoundedInstallationBody.Rejected -> ComplaintSessionResult.Failed(body.reason)
            is BoundedInstallationBody.Verified ->
                if (response.status == HttpStatusCode.OK) {
                    ComplaintSessionResponse.decode(body.text, expected)
                } else {
                    ComplaintSessionResult.HttpFailure(response.status.value)
                }
        }

    suspend fun bootstrap(
        response: HttpResponse,
        endpoint: ComplaintBackendEndpoint,
    ): InstallationEnrollmentResult<InstallationBootstrapResponse> =
        when (
            val body =
                ComplaintBoundedInstallationBody.read(response, endpoint, ComplaintInstallationResponseRoute.BOOTSTRAP)
        ) {
            is BoundedInstallationBody.Rejected -> InstallationEnrollmentResult.Failed(body.reason)
            is BoundedInstallationBody.Verified ->
                if (response.status == HttpStatusCode.OK) {
                    InstallationBootstrapResponse.decode(body.text)
                } else {
                    InstallationEnrollmentResult.HttpFailure(response.status.value)
                }
        }

    /** Parse the entire session response against the durable binding, then deliberately discard its token. */
    suspend fun enrollment(
        response: HttpResponse,
        endpoint: ComplaintBackendEndpoint,
        expected: InstallationCredentialRecord,
    ): InstallationEnrollmentResult<Unit> {
        val route = ComplaintInstallationResponseRoute.ENROLLMENT
        return when (val body = ComplaintBoundedInstallationBody.read(response, endpoint, route)) {
            is BoundedInstallationBody.Rejected -> InstallationEnrollmentResult.Failed(body.reason)
            is BoundedInstallationBody.Verified ->
                if (route.isSuccess(response.status)) {
                    discardEnrollmentToken(ComplaintSessionResponse.decode(body.text, expected))
                } else {
                    InstallationEnrollmentResult.HttpFailure(response.status.value)
                }
        }
    }

    private fun discardEnrollmentToken(result: ComplaintSessionResult): InstallationEnrollmentResult<Unit> =
        when (result) {
            is ComplaintSessionResult.Ready -> InstallationEnrollmentResult.Ready(Unit)
            is ComplaintSessionResult.Failed -> InstallationEnrollmentResult.Failed(result.reason)
            is ComplaintSessionResult.HttpFailure -> InstallationEnrollmentResult.HttpFailure(result.status)
            is ComplaintSessionResult.LocalFailure -> InstallationEnrollmentResult.LocalFailure(result.outcome)
        }
}
