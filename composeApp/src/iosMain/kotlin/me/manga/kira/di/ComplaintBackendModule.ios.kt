package me.manga.kira.di

import me.manga.kira.core.result.AppResult
import me.manga.kira.data.remote.complaint.createIosComplaintEnrollmentEngineOwner
import me.manga.kira.data.remote.complaint.createIosComplaintHistoryEngineOwner
import me.manga.kira.data.remote.complaint.createIosComplaintSessionEngineOwner
import me.manga.kira.platform.storage.IosInstallationCredentialMaterialGenerator
import me.manga.kira.platform.storage.IosInstallationCredentialStore
import me.manga.kira.platform.storage.IosPendingComplaintActionStore
import me.manga.kira.sources.runtime.GeneratedSourceRemoteConfig

/**
 * Not referenced by the shipping iOS graph. Future activation must supply the independently
 * verified signing/default-access-group value; this slice neither guesses nor copies entitlements.
 */
internal fun unselectedIosComplaintHistoryGraph(
    expectedDefaultAccessGroup: String,
): AppResult<ComplaintBackendGraph> = selectComplaintBackendCandidate {
    createComplaintBackendGraph(
        baseUrl = { GeneratedSourceRemoteConfig.BASE_URL },
        resources = ComplaintBackendResources(
            credentials = { IosInstallationCredentialStore(expectedDefaultAccessGroup) },
            pending = { IosPendingComplaintActionStore(expectedDefaultAccessGroup) },
            generator = { IosInstallationCredentialMaterialGenerator() },
            enrollmentEngine = ::createIosComplaintEnrollmentEngineOwner,
            sessionEngine = ::createIosComplaintSessionEngineOwner,
            historyEngine = ::createIosComplaintHistoryEngineOwner,
        ),
    )
}
