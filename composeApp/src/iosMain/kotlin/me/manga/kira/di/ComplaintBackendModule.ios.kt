package me.manga.kira.di

import me.manga.kira.core.result.AppResult
import me.manga.kira.data.complaint.backend.ComplaintReportIdentifiers
import me.manga.kira.data.complaint.backend.ComplaintReportInputs
import me.manga.kira.data.complaint.backend.ComplaintReportMetadataInput
import me.manga.kira.data.remote.complaint.createIosComplaintDeletionEngineOwner
import me.manga.kira.data.remote.complaint.createIosComplaintEnrollmentEngineOwner
import me.manga.kira.data.remote.complaint.createIosComplaintHistoryEngineOwner
import me.manga.kira.data.remote.complaint.createIosComplaintMutationEngineOwner
import me.manga.kira.data.remote.complaint.createIosComplaintSessionEngineOwner
import me.manga.kira.platform.storage.IosInstallationCredentialMaterialGenerator
import me.manga.kira.platform.storage.IosInstallationCredentialStore
import me.manga.kira.platform.storage.IosPendingComplaintActionStore
import me.manga.kira.platform.version.IosAppVersionProvider
import platform.Foundation.NSBundle
import platform.Foundation.NSUUID
import platform.UIKit.UIDevice
import kotlin.experimental.ExperimentalNativeApi
import kotlin.native.Platform

/**
 * Actual startup selection. The generated launch must match separately supplied signing evidence
 * and the verified default group; no value is guessed or copied from a production entitlement.
 */
@OptIn(ExperimentalNativeApi::class)
internal fun iosComplaintBackendGraph(): AppResult<ComplaintBackendGraph> =
    selectComplaintBackendCandidate(runtime = {
        ComplaintBackendRuntime(
            ComplaintBackendPlatform.IOS,
            NSBundle.mainBundle.bundleIdentifier.orEmpty(),
            isDebug = Platform.isDebugBinary,
        )
    }) { target ->
        createComplaintBackendGraph(
            baseUrl = { target.sourceBackend },
            expectedDataScopeId = target.dataScopeId,
            resources =
                ComplaintBackendResources(
                    credentials = { IosInstallationCredentialStore(target.iosDefaultAccessGroup) },
                    pending = { IosPendingComplaintActionStore(target.iosDefaultAccessGroup) },
                    generator = { IosInstallationCredentialMaterialGenerator() },
                    engines =
                        ComplaintBackendEngineFactories(
                            enrollment = ::createIosComplaintEnrollmentEngineOwner,
                            session = ::createIosComplaintSessionEngineOwner,
                            history = ::createIosComplaintHistoryEngineOwner,
                            mutation = ::createIosComplaintMutationEngineOwner,
                            deletion = ::createIosComplaintDeletionEngineOwner,
                        ),
                    inputs =
                        ComplaintBackendInputFactories(
                            reports = ::iosComplaintReportInputs,
                            deletionKey = { NSUUID().UUIDString.lowercase() },
                        ),
                ),
        )
    }

/** Public operation UUIDs are unrelated to credentials; edit never allocates a content ID or reads UIDevice. */
private fun iosComplaintReportInputs(): ComplaintReportInputs =
    ComplaintReportInputs(
        identifiers = {
            ComplaintReportIdentifiers(
                clientId = NSUUID().UUIDString.lowercase(),
                idempotencyKey = NSUUID().UUIDString.lowercase(),
            )
        },
        metadata = {
            val device = UIDevice.currentDevice
            ComplaintReportMetadataInput(
                appVersion = IosAppVersionProvider().versionName,
                osVersion = device.systemVersion,
                manufacturer = "Apple",
                deviceModel = device.model,
            )
        },
        editKey = { NSUUID().UUIDString.lowercase() },
    )
