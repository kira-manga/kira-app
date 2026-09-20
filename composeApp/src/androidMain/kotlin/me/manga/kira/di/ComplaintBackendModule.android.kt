package me.manga.kira.di

import android.content.Context
import android.content.pm.ApplicationInfo
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
import java.util.UUID

/** Actual startup selection; context is not resolved at all for the default Disabled launch. */
internal fun androidComplaintBackendGraph(context: () -> Context): AppResult<ComplaintBackendGraph> =
    selectComplaintBackendCandidate(runtime = {
        val application = context().applicationContext
        ComplaintBackendRuntime(
            ComplaintBackendPlatform.ANDROID,
            application.packageName,
            isDebug = (application.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0,
        )
    }) { target ->
        createComplaintBackendGraph(
            baseUrl = { target.sourceBackend },
            expectedDataScopeId = target.dataScopeId,
            resources =
                ComplaintBackendResources(
                    credentials = { AndroidInstallationCredentialStore(context().applicationContext) },
                    pending = { AndroidPendingComplaintActionStore(context().applicationContext) },
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
                            reports = { androidComplaintReportInputs(context()) },
                            deletionKey = { UUID.randomUUID().toString() },
                        ),
                ),
        )
    }

/** No eager ID/metadata work; edit's one key never invokes the creation pair or diagnostic supplier. */
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
        editKey = { UUID.randomUUID().toString() },
    )
