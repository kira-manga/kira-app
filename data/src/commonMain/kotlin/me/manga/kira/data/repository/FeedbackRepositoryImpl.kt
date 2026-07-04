package me.manga.kira.data.repository

import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import me.manga.kira.core.util.runCatchingCancellable
import me.manga.kira.domain.auth.UserIdProvider
import me.manga.kira.domain.device.DeviceInfoProvider
import me.manga.kira.domain.model.complaint.ComplaintType
import me.manga.kira.domain.repository.FeedbackRepository
import me.manga.kira.presentation.features.complaint.model.Complaint
import me.manga.kira.presentation.features.complaint.model.ComplaintStatus
import me.manga.kira.presentation.features.complaint.usecase.SendComplaintUseCase as LegacySendComplaintUseCase
import me.manga.kira.presentation.features.complaint.model.ComplaintType as LegacyComplaintType

/**
 * [FeedbackRepository] strangler-fig delegate over the legacy `:shared`
 * [LegacySendComplaintUseCase] + [UserIdProvider] + [DeviceInfoProvider].
 *
 * Phase 7.x.language.request rework (introduction) + Phase 7.x.settings.feedback (extension).
 * The extension generalised the impl from a language-pinned `sendLanguageRequest(body)` to a
 * generic `submit(type, subject, body)`; the language-request entry now flows through the same
 * orchestration via the interface's default-impl wrapper.
 *
 * **Strangler-fig posture**: same shape as
 * [LanguageRepositoryImpl] / [ReadingSessionRepositoryImpl] / [ReadingStatisticsRepositoryImpl]
 * — `:data` reaches into `:shared`'s legacy code for cross-cutting state/operations that haven't
 * been ported. The fan-in here is 3 :shared types (`SendComplaintUseCase`, `UserIdProvider`,
 * `DeviceInfoProvider`) because the legacy
 * [me.manga.kira.presentation.features.complaint.viewmodel.ComplaintViewModel.submit]
 * already orchestrates these three collaborators (see lines 52-72 of
 * `shared/.../complaint/viewmodel/ComplaintViewModel.kt`); the rework `:data` impl owns the
 * same orchestration so the rework `:presentation` layer never sees the legacy types.
 *
 * **Orchestration mirrors the legacy `ComplaintViewModel.submit`**:
 *  1. `userIdProvider.getUserId()` — platform-stable user/device ID (Android: `Settings.Secure.
 *     ANDROID_ID`; iOS: `identifierForVendor`; Desktop: per-install UUID).
 *  2. `deviceInfoProvider.getDeviceMetadata()` — manufacturer / model / OS version / app
 *     version map.
 *  3. Assemble [Complaint] with:
 *     - `userId` from step 1
 *     - `type` = caller-provided (mapped from the `:domain` [ComplaintType] enum to the
 *       legacy `:shared` [LegacyComplaintType] by name; the two enums share identity and order
 *       per the `ComplaintSummary.kt` KDoc, so `enumValueOf` is a safe 1:1 mapping).
 *     - `subject` = caller-provided
 *     - `body` = caller-provided
 *     - `createdAt` = `Clock.System.now()`
 *     - `status` = `ComplaintStatus.OPEN`
 *     - `metadata` = step 2
 *  4. `legacy(complaint)` — invokes the legacy `SendComplaintUseCase`, which validates
 *     `subject.isNotBlank()` + `body.length >= 8` and writes to Firestore.
 *
 * The entire orchestration is wrapped in `runCatching {}`. Any throw from any step
 * (`IllegalArgumentException` from the legacy `require` blocks, Firestore network/permission
 * failures, the `enumValueOf` mapping should never throw given the shared identity but is
 * inside the catch anyway, etc.) surfaces as [Result.failure]. The VM emits a single failure
 * effect regardless of the failure mode.
 *
 * **Discarded Firestore document ID**: the legacy use case returns the Firestore document ID
 * (a `String`); the rework discards it because the VM has no use for it — the success
 * confirmation snackbar doesn't reference the ID. If a future slice needs the ID
 * (e.g., "you can track your request with reference #abc123"), change the interface return
 * type to `Result<String>` and propagate through.
 *
 * **`@OptIn(ExperimentalTime::class)`**: the legacy `Complaint.createdAt` field is
 * `kotlin.time.Instant?` (Phase 4 batch 4.4 migration note in `Complaint.kt`); constructing the
 * instant requires the opt-in. Same opt-in posture as the legacy `ComplaintViewModel`.
 *
 * **Import-alias `LegacySendComplaintUseCase` / `LegacyComplaintType`**: avoids ambiguity with
 * the rework `:domain` types of the same name. Same posture as `LegacySettingsRepository` in
 * [LanguageRepositoryImpl].
 *
 * **SRP (contract §6)**: owns ONE rule — "assemble a generic `Complaint` payload from
 * caller-supplied inputs and delegate to the legacy use case". The Firestore plumbing lives in
 * `ComplaintFirestoreDataSource` / `ComplaintFirestoreRestDataSource` (untouched by this slice);
 * the user-ID and device-metadata lookups live in their respective platform impls (untouched).
 *
 * **DIP (contract §6)**: implements the [FeedbackRepository] interface from `:domain`. The
 * `:domain` interface is the seam — `:presentation` / `:ui` never see this impl, only the
 * interface. The 3 :shared deps are constructor-injected by Koin at the composition root.
 *
 * **Lifecycle**: `single` in Koin (per [FeedbackRepository] KDoc). All three :shared deps are
 * singletons in `SharedModule`; instantiating the impl is a no-op cost, but `single` keeps the
 * VM-shared instance consistent across recompositions.
 *
 * **Load-bearing fixes preserved**: this slice does NOT touch the Coil ImageLoader, the per-host
 * repo registry, OkHttp interceptor, AVIF decoder, HighQualitySkiaImageDecoder, or `:platform`
 * — Feedback submission is pure Firestore-bound complaint submission. No load-bearing risk.
 *
 * **Audit-trail postscript** (Phase 9.x.cluster6.staleKdocSweep.cascade,
 * Task #462, 2026-05-28): three stale citations into the §363-retired
 * legacy `:shared/.../complaint/viewmodel/ComplaintViewModel.kt`
 * appear above:
 *  - Lines 27-31 (strangler-fig fan-in rationale): "because the legacy
 *    `[me.manga.kira.presentation.features.complaint.viewmodel.
 *    ComplaintViewModel.submit]` already orchestrates these three
 *    collaborators (see lines 52-72 of
 *    `shared/.../complaint/viewmodel/ComplaintViewModel.kt`); the
 *    rework `:data` impl owns the same orchestration so the rework
 *    `:presentation` layer never sees the legacy types".
 *  - Line 33 (orchestration section header): "**Orchestration mirrors
 *    the legacy `ComplaintViewModel.submit`**".
 *  - Line 65 (`@OptIn` rationale closer): "Same opt-in posture as the
 *    legacy `ComplaintViewModel`".
 * The legacy `:shared/.../complaint/viewmodel/ComplaintViewModel.kt`
 * was retired in Phase 9.x.complaintvm.retire (§363 sweep, commit
 * `e2af0d4` "(1/2): delete unreachable :shared ComplaintViewModel");
 * verified by a filesystem check returning zero hits for that path.
 * The 4-step orchestration rationale (userId → metadata → assemble →
 * legacy use case) and the @OptIn(ExperimentalTime::class) requirement
 * (driven by `Complaint.createdAt: kotlin.time.Instant?` per Phase 4
 * batch 4.4) both stand on their own merits — the rework
 * FeedbackRepositoryImpl owns the orchestration directly via the
 * `:shared` `SendComplaintUseCase` / `UserIdProvider` /
 * `DeviceInfoProvider` triple (which all REMAIN LIVE post-§363 retire;
 * only the legacy VM that previously orchestrated them was the
 * unreachable orphan). The "lines 52-72" line anchor is historical
 * record of the orchestration survey captured before the §363 retire;
 * the 4-step structure documented inline above is the authoritative
 * specification post-retire. Original §253-era prose preserved
 * verbatim per the audit-trail-preservation convention — the citations
 * are historical record of the design lineage; the rework
 * FeedbackRepositoryImpl continues to submit complaints via the same
 * Firestore-bound use case past the legacy VM retire.
 */
@OptIn(ExperimentalTime::class)
class FeedbackRepositoryImpl(
    private val sendComplaint: LegacySendComplaintUseCase,
    private val userIdProvider: UserIdProvider,
    private val deviceInfoProvider: DeviceInfoProvider,
) : FeedbackRepository {

    override suspend fun submit(
        type: ComplaintType,
        subject: String,
        body: String,
    ): Result<Unit> = runCatchingCancellable {
        val complaint = Complaint(
            userId = userIdProvider.getUserId(),
            type = LegacyComplaintType.valueOf(type.name),
            subject = subject,
            body = body,
            createdAt = Clock.System.now(),
            status = ComplaintStatus.OPEN,
            metadata = deviceInfoProvider.getDeviceMetadata(),
        )
        sendComplaint(complaint)
    }
}
