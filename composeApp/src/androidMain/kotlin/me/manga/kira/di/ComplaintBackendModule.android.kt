package me.manga.kira.di

import android.content.Context
import me.manga.kira.core.result.AppResult
import me.manga.kira.data.remote.complaint.createAndroidComplaintEnrollmentEngineOwner
import me.manga.kira.data.remote.complaint.createAndroidComplaintHistoryEngineOwner
import me.manga.kira.data.remote.complaint.createAndroidComplaintSessionEngineOwner
import me.manga.kira.platform.storage.AndroidInstallationCredentialMaterialGenerator
import me.manga.kira.platform.storage.AndroidInstallationCredentialStore
import me.manga.kira.platform.storage.AndroidPendingComplaintActionStore
import me.manga.kira.sources.runtime.GeneratedSourceRemoteConfig

/** Not referenced by the shipping Android graph; ordinary Debug is disabled before allocation. */
internal fun unselectedAndroidComplaintHistoryGraph(context: Context): AppResult<ComplaintBackendGraph> =
    selectComplaintBackendCandidate {
        createComplaintBackendGraph(
            baseUrl = { GeneratedSourceRemoteConfig.BASE_URL },
            resources = ComplaintBackendResources(
                credentials = { AndroidInstallationCredentialStore(context.applicationContext) },
                pending = { AndroidPendingComplaintActionStore(context.applicationContext) },
                generator = { AndroidInstallationCredentialMaterialGenerator() },
                enrollmentEngine = ::createAndroidComplaintEnrollmentEngineOwner,
                sessionEngine = ::createAndroidComplaintSessionEngineOwner,
                historyEngine = ::createAndroidComplaintHistoryEngineOwner,
            ),
        )
    }
