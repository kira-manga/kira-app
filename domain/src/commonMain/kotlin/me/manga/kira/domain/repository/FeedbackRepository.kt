package me.manga.kira.domain.repository

import me.manga.kira.domain.model.complaint.ComplaintType

/**
 * User-feedback submission surface — writes a complaint payload to the remote complaint store
 * (Firestore, behind the legacy `:shared`
 * [me.manga.kira.presentation.features.complaint.usecase.SendComplaintUseCase]).
 *
 * Phase 7.x.language.request rework (introduction) + Phase 7.x.settings.feedback (extension).
 * The `:data` impl strangler-fig delegates to:
 *  - `:shared`/`SendComplaintUseCase` — the actual Firestore-bound write
 *  - `:shared`/`UserIdProvider` — to populate `Complaint.userId`
 *  - `:shared`/`DeviceInfoProvider` — to populate `Complaint.metadata`
 *
 * Same strangler-fig posture as
 * [me.manga.kira.domain.repository.LanguageRepository] (1 :shared dep) and
 * [me.manga.kira.domain.repository.ReadingStatisticsRepository] (1 :shared dep), but with a
 * 3-dep fan-in because the legacy [me.manga.kira.presentation.features.complaint.viewmodel.
 * ComplaintViewModel.submit] orchestrates these three collaborators today (lines 52-72 of
 * `shared/.../complaint/viewmodel/ComplaintViewModel.kt`); the rework `:data` impl owns the same
 * orchestration so the rework `:presentation` layer never sees the legacy types.
 *
 * Contract §6 SRP: owns ONE rule — "assemble a `Complaint` payload and submit it to the remote
 * store, reporting success/failure to the caller". Validation lives in the legacy
 * `SendComplaintUseCase`'s `require(body.length >= 8)` block; the impl's `runCatching` wrapper
 * surfaces any failure (validation OR network OR Firestore) as [Result.failure]. The repository
 * does not gate on body length — that's the use case's job; the repository is a pure transport.
 *
 * Contract §6 ISP: two methods — [submit] is the generic form; [sendLanguageRequest] is a
 * convenience pin for the language-picker surface that doesn't need to know about complaint
 * types or subjects. The default-impl wrapper means the language picker's call site stays
 * unchanged and ISP is preserved per-consumer: the language picker depends on the narrow
 * `sendLanguageRequest(body)` signature; the settings hub depends on the broader
 * `submit(type, subject, body)`. Both flows route through the same `:data` orchestration —
 * Phase 7.x.settings.feedback retired the original "sibling repository" forecast by
 * implementing the generic shape directly on this interface (the duplication a sibling would
 * have introduced in the `:data` layer outweighs the slight ISP tension here).
 *
 * Contract §6 DIP: consumers depend on this interface, never on the legacy facade or Firestore
 * directly. Koin binds the impl at the composition root in `languageReworkModule` (the
 * `single<FeedbackRepository>` binding from the request slice carries over verbatim — settings
 * resolves it transitively via `get()` without re-binding).
 *
 * Lifecycle expectation: the impl is bound as a `single` (matching the upstream legacy
 * collaborators' singletons from `SharedModule`). A `factory` would re-create the impl on each
 * resolution — wasteful for a stateless transport whose collaborators are themselves singletons.
 *
 * Behavior preservation: BOTH rework entries write to the SAME Firestore collection as the
 * legacy screens' `FeedbackDialog`. The admin dashboard reads either path's submissions
 * identically — same `userId`, same `metadata` device-info shape, same lifecycle. The
 * language-request path uses `type = LANGUAGES` + `subject = "Languages"` (same as legacy
 * language-request); the settings path passes through the user-selected `ComplaintType` and a
 * subject derived from the type's `name` (mirroring legacy `SettingsScreen.kt:374-376`'s
 * `complaintViewModel.submit(it, it.name, body, ...)` pattern — Phase 10 i18n lift re-points
 * the subject to the localized display name).
 *
 * **Why not extend `LanguageRepository`?** The language picker's persistence surface (DataStore
 * pref + locale switch) and its complaint-submission surface have NOTHING in common at the
 * `:data` level — different upstream stores (DataStore vs Firestore), different lifecycles
 * (sync read + suspend write vs suspend-only write), different failure modes (DataStore writes
 * effectively never fail vs Firestore writes commonly fail). Bundling them onto one interface
 * would conflate persistence concerns and force every consumer through a `Result<Unit>` return
 * type for the DataStore writes (which never need it). Two interfaces, one feature slice — the
 * `LanguageViewModel` consumes both via its 4 use case constructor params.
 *
 * **Audit-trail postscript** (Phase 9.x.settingsabout.staleKdocSweep.cascade,
 * Task #453, 2026-05-28): three stale legacy-path citations above were
 * cascade-rendered stale by two retire events:
 *  - Lines 19-21 cite "the legacy [me.manga.kira.presentation.features.
 *    complaint.viewmodel.ComplaintViewModel.submit] orchestrates these
 *    three collaborators today (lines 52-72 of `shared/.../complaint/
 *    viewmodel/ComplaintViewModel.kt`)" — that legacy VM file was retired
 *    in Phase 9.x.complaintvm.retire (§363, commit `e2af0d4`); verified by
 *    a filesystem check returning zero hits.
 *  - Line 50 cites "the legacy screens' `FeedbackDialog`" — and line 91
 *    cites "the UI's `FeedbackDialog` (legacy) gates submission at length
 *    5". The legacy `composeApp/.../presentation/common/componants/dialogs/
 *    FeedbackDialog.kt` was retired in Phase 9.x.settings_about.legacyui.
 *    retire (§354, commit `b0387cb`); verified by a filesystem check
 *    returning zero hits.
 *  - Line 54 cites "legacy `SettingsScreen.kt:374-376`'s
 *    `complaintViewModel.submit(it, it.name, body, ...)` pattern" — the
 *    legacy `composeApp/.../features/settings/ui/screens/SettingsScreen.kt`
 *    was retired in the same §354 multi-commit chain (commit `5cc42d2`);
 *    verified by a filesystem check returning zero hits.
 * The strangler-fig orchestration rationale (`:data` impl assembles the
 * `Complaint` payload + calls `SendComplaintUseCase`) stands on its own
 * merits — the rework `:data` impl owns the orchestration that the cited
 * legacy VM used to drive, and the orchestration is documented inline
 * above (assemble payload, call use case, surface as Result). The validation
 * gates (`>= 8` server-side, `>= 5` UI) are preserved by the rework UI's
 * own equivalent dialog (mounted from the rework `:ui` Settings hub). The
 * `subject = type.name` pattern from §354 is preserved by the rework call
 * site verbatim (Phase 10 i18n lift re-points the subject to the localized
 * display name). Original §253-era prose preserved verbatim per the audit-
 * trail-preservation convention — the citations are historical record of
 * the design lineage; the repository continues to submit correctly through
 * the legacy retire.
 */
interface FeedbackRepository {

    /**
     * Submit a generic feedback complaint to the remote store.
     *
     * Phase 7.x.settings.feedback — the generic submission surface used by the Settings hub's
     * "Request feature / bug" dialog. The user selects a [ComplaintType] from a dropdown
     * (TECHNICAL / LANGUAGES / SITES_ADD / SITE_ERROR / FEATURES / CUSTOM) and types a free-form
     * body; the call site derives [subject] from the type's localized display name (or its
     * `name` literal pre-Phase 10).
     *
     * The impl assembles a full
     * [me.manga.kira.presentation.features.complaint.model.Complaint] payload:
     *  - `userId` = current platform-unique device/user identifier.
     *  - `type` = mapped from the [ComplaintType] domain enum to the legacy `:shared` enum by
     *    name (the two enums share identity and order — see
     *    [me.manga.kira.domain.model.complaint.ComplaintType] KDoc).
     *  - `subject` = passed-through verbatim.
     *  - `body` = passed-through verbatim.
     *  - `createdAt` = `Clock.System.now()` — set inside the impl, not by the caller.
     *  - `status` = `OPEN` — default.
     *  - `metadata` = `DeviceInfoProvider.getDeviceMetadata()`.
     *
     * Validation: the legacy `SendComplaintUseCase` requires `body.length >= 8` and
     * `subject.isNotBlank()`. The UI's `FeedbackDialog` (legacy) gates submission at length 5;
     * bodies in the 5-7 range will reach the legacy validator and fail — the rework dialog
     * mirrors the legacy's `>= 5` UI gate, and any failures surface as a snackbar effect.
     *
     * Result semantics:
     *  - [Result.success] — Firestore write committed.
     *  - [Result.failure] — any failure: validation, network, Firestore, etc.
     *
     * @param type the user-selected category.
     * @param subject short header line (typically the type's display name).
     * @param body free-form user-typed description.
     * @return [Result.success] on commit; [Result.failure] on any throw.
     */
    suspend fun submit(type: ComplaintType, subject: String, body: String): Result<Unit>

    /**
     * Submit a "Please add support for this language" request to the remote complaint store.
     *
     * Convenience wrapper for the language-picker surface — pins the type to
     * [ComplaintType.LANGUAGES] and the subject to `"Languages"`, then delegates to [submit].
     * The default impl keeps the existing language-request call site
     * ([me.manga.kira.domain.usecase.feedback.SendLanguageRequestUseCase] +
     * [me.manga.kira.presentation.language.LanguageViewModel]) unchanged — they continue to
     * depend on the narrow `sendLanguageRequest(body)` signature, and the orchestration routes
     * through the same generic [submit] underneath.
     *
     * Phase 10 i18n lift will replace the `"Languages"` literal with a localized display-name
     * lookup, in lockstep with the legacy `ComplaintType.LANGUAGES.displayName()` path.
     *
     * @param body free-form user-typed text describing the requested language.
     * @return [Result.success] on commit; [Result.failure] on any throw.
     */
    suspend fun sendLanguageRequest(body: String): Result<Unit> =
        submit(type = ComplaintType.LANGUAGES, subject = LANGUAGE_REQUEST_SUBJECT, body = body)

    companion object {
        /**
         * English literal for the language-request convenience wrapper's subject. Phase 10
         * i18n lift re-points to `Res.string.*` (matching the legacy
         * `ComplaintType.LANGUAGES.displayName()` per-locale lookup).
         */
        const val LANGUAGE_REQUEST_SUBJECT: String = "Languages"
    }
}
