package me.manga.kira.di

import me.manga.kira.core.result.AppResult
import me.manga.kira.data.complaint.backend.ComplaintReportIdentifiers
import me.manga.kira.data.complaint.backend.ComplaintReportInputs
import me.manga.kira.data.complaint.backend.ComplaintReportMetadataInput
import me.manga.kira.data.remote.complaint.createIosComplaintEnrollmentEngineOwner
import me.manga.kira.data.remote.complaint.createIosComplaintDeletionEngineOwner
import me.manga.kira.data.remote.complaint.createIosComplaintHistoryEngineOwner
import me.manga.kira.data.remote.complaint.createIosComplaintMutationEngineOwner
import me.manga.kira.data.remote.complaint.createIosComplaintSessionEngineOwner
import me.manga.kira.platform.storage.IosInstallationCredentialMaterialGenerator
import me.manga.kira.platform.storage.IosInstallationCredentialStore
import me.manga.kira.platform.storage.IosPendingComplaintActionStore
import me.manga.kira.platform.version.IosAppVersionProvider
import me.manga.kira.sources.runtime.GeneratedSourceRemoteConfig
import platform.Foundation.NSUUID
import platform.UIKit.UIDevice

/**
 * Not referenced by the shipping iOS graph. Future activation must supply the independently
 * verified signing/default-access-group value; this slice neither guesses nor copies entitlements.
 */
internal fun unselectedIosComplaintHistoryGraph(expectedDefaultAccessGroup: String): AppResult<ComplaintBackendGraph> =
    selectComplaintBackendCandidate {
        createComplaintBackendGraph(
            baseUrl = { GeneratedSourceRemoteConfig.BASE_URL },
            resources =
                ComplaintBackendResources(
                    credentials = { IosInstallationCredentialStore(expectedDefaultAccessGroup) },
                    pending = { IosPendingComplaintActionStore(expectedDefaultAccessGroup) },
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

/** Public report UUIDs are unrelated to installation credentials; all reads remain prepare-only. */
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
    )
