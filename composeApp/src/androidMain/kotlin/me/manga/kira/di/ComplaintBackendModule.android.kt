package me.manga.kira.di

import android.content.Context
import android.os.Build
import me.manga.kira.core.result.AppResult
import me.manga.kira.data.complaint.backend.ComplaintReportIdentifiers
import me.manga.kira.data.complaint.backend.ComplaintReportInputs
import me.manga.kira.data.complaint.backend.ComplaintReportMetadataInput
import me.manga.kira.data.remote.complaint.createAndroidComplaintDeletionEngineOwner
import me.manga.kira.data.remote.complaint.createAndroidComplaintEnrollmentEngineOwner
import me.manga.kira.data.remote.complaint.createAndroidComplaintHistoryEngineOwner
import me.manga.kira.data.remote.complaint.createAndroidComplaintMutationEngineOwner
import me.manga.kira.data.remote.complaint.createAndroidComplaintSessionEngineOwner
import me.manga.kira.platform.storage.AndroidInstallationCredentialMaterialGenerator
import me.manga.kira.platform.storage.AndroidInstallationCredentialStore
import me.manga.kira.platform.storage.AndroidPendingComplaintActionStore
import me.manga.kira.platform.version.AndroidAppVersionProvider
import me.manga.kira.sources.runtime.GeneratedSourceRemoteConfig
import java.util.UUID

/** Not referenced by the shipping Android graph; ordinary Debug is disabled before allocation. */
internal fun unselectedAndroidComplaintHistoryGraph(context: Context): AppResult<ComplaintBackendGraph> =
    selectComplaintBackendCandidate {
        createComplaintBackendGraph(
            baseUrl = { GeneratedSourceRemoteConfig.BASE_URL },
            resources =
                ComplaintBackendResources(
                    credentials = { AndroidInstallationCredentialStore(context.applicationContext) },
                    pending = { AndroidPendingComplaintActionStore(context.applicationContext) },
                    generator = { AndroidInstallationCredentialMaterialGenerator() },
                    engines =
                        ComplaintBackendEngineFactories(
                            enrollment = ::createAndroidComplaintEnrollmentEngineOwner,
                            session = ::createAndroidComplaintSessionEngineOwner,
                            history = ::createAndroidComplaintHistoryEngineOwner,
                            mutation = ::createAndroidComplaintMutationEngineOwner,
                            deletion = ::createAndroidComplaintDeletionEngineOwner,
                        ),
                    inputs =
                        ComplaintBackendInputFactories(
                            reports = { androidComplaintReportInputs(context) },
                            deletionKey = { UUID.randomUUID().toString() },
                        ),
                ),
        )
    }

/** No ID generation or metadata read until the data facade explicitly prepares a new live report. */
private fun androidComplaintReportInputs(context: Context): ComplaintReportInputs =
    ComplaintReportInputs(
        identifiers = {
            ComplaintReportIdentifiers(
                clientId = UUID.randomUUID().toString(),
                idempotencyKey = UUID.randomUUID().toString(),
            )
        },
        metadata = {
            ComplaintReportMetadataInput(
                appVersion = AndroidAppVersionProvider(context).versionName,
                osVersion = Build.VERSION.RELEASE,
                manufacturer = Build.MANUFACTURER,
                deviceModel = Build.MODEL,
            )
        },
    )
