package me.manga.kira.data.complaint.backend

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import me.manga.kira.platform.storage.CredentialReadResult
import me.manga.kira.platform.storage.InstallationCredentialMaterialGenerator
import me.manga.kira.platform.storage.InstallationCredentialRecord
import me.manga.kira.platform.storage.InstallationCredentialStore
import me.manga.kira.platform.storage.InstallationMaterialGenerationResult
import me.manga.kira.platform.storage.InstallationStorageFailure
import me.manga.kira.platform.storage.PendingComplaintActionStore

/**
 * Fixed enrollment mechanics only, invoked by the coordinator while it holds its existing mutex
 * and total deadline. Owns no lock, consent, cleanup, permission callback or lifecycle state.
 * The coordinator retains admission immediately before POST and after a successful response.
 * Blocking OS calls/native drain are not preempted; a qualified engine and later session are still required.
 */
internal class InstallationEnrollmentAttempt(
    private val credentials: InstallationCredentialStore,
    private val pending: PendingComplaintActionStore,
    private val http: InstallationEnrollmentHttp,
    private val generator: InstallationCredentialMaterialGenerator,
) {
    suspend fun prepare(): InstallationEnrollmentResult<InstallationCredentialRecord> {
        credentials.requireNoCleanupMarker()
        pending.requireEmptyPending()
        currentCoroutineContext().ensureActive()
        if (http.isClosed) return InstallationEnrollmentResult.Failed(ComplaintSessionFailure.CLOSED)
        return when (val read = credentials.read()) {
            is InstallationStorageFailure -> fail(read)
            is CredentialReadResult.Present -> InstallationEnrollmentResult.Ready(read.record.also(::active))
            CredentialReadResult.Missing ->
                when (val bootstrap = http.bootstrap()) {
                    is InstallationEnrollmentResult.Failure -> bootstrap
                    is InstallationEnrollmentResult.Ready -> createRecord(bootstrap.value)
                }
        }
    }

    suspend fun send(record: InstallationCredentialRecord): InstallationEnrollmentResult<Unit> {
        currentCoroutineContext().ensureActive()
        return http.enroll(record)
    }

    /** Does not authorize publication: the caller has already performed its final exact admission. */
    suspend fun checkPublication(result: InstallationEnrollmentResult<Unit>): InstallationEnrollmentResult<Unit> {
        if (result !is InstallationEnrollmentResult.Ready) return result
        currentCoroutineContext().ensureActive()
        return if (http.isClosed) InstallationEnrollmentResult.Failed(ComplaintSessionFailure.CLOSED) else result
    }

    private suspend fun createRecord(
        bootstrap: InstallationBootstrapResponse,
    ): InstallationEnrollmentResult<InstallationCredentialRecord> {
        credentials.requireNoCleanupMarker()
        pending.requireEmptyPending()
        currentCoroutineContext().ensureActive()
        if (http.isClosed) return InstallationEnrollmentResult.Failed(ComplaintSessionFailure.CLOSED)
        val generated = generator.generate(bootstrap.dataScopeId)
        currentCoroutineContext().ensureActive()
        return if (http.isClosed) {
            InstallationEnrollmentResult.Failed(ComplaintSessionFailure.CLOSED)
        } else {
            when (generated) {
                is InstallationMaterialGenerationResult.Generated -> storeGenerated(bootstrap, generated)
                InstallationMaterialGenerationResult.InvalidScope ->
                    InstallationEnrollmentResult.MaterialFailure(InstallationEnrollmentMaterialFailure.INVALID_SCOPE)
                InstallationMaterialGenerationResult.EntropyFailure ->
                    InstallationEnrollmentResult.MaterialFailure(InstallationEnrollmentMaterialFailure.ENTROPY)
                InstallationMaterialGenerationResult.Unsupported ->
                    InstallationEnrollmentResult.MaterialFailure(InstallationEnrollmentMaterialFailure.UNSUPPORTED)
            }
        }
    }

    private suspend fun storeGenerated(
        bootstrap: InstallationBootstrapResponse,
        generated: InstallationMaterialGenerationResult.Generated,
    ): InstallationEnrollmentResult<InstallationCredentialRecord> =
        if (generated.material.dataScopeId != bootstrap.dataScopeId) {
            InstallationEnrollmentResult.MaterialFailure(InstallationEnrollmentMaterialFailure.INVALID_SCOPE)
        } else {
            val record =
                credentials.createCoordinated(InstallationCredentialRecord.candidate(generated.material)).also(::active)
            InstallationEnrollmentResult.Ready(record)
        }
}
