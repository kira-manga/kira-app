package me.manga.kira.presentation.sources

import me.manga.kira.domain.model.sources.Source
import me.manga.kira.presentation.mvi.MviIntent

/**
 * User actions submitted from the Sources screen.
 *
 * Phase 7.x.sources rework. Sealed so the [SourcesViewModel.handle] `when` is exhaustive;
 * adding a new action requires adding a new subclass (OCP — compile-time enforcement that the
 * reducer handles every case).
 *
 * **Foundation variants** (toggle mutators):
 *  - [OnToggleSource] — flip one source's enabled state.
 *  - [OnToggleLanguage] — flip every source in a language together.
 *
 * **Phase 7.x.sources.complaint variants** (per [PLAN_sources.md] §84.8's named OCP-extension
 * hook, mirroring [me.manga.kira.presentation.settings.SettingsIntent]'s feedback-dialog
 * triplet from Phase 7.x.settings.feedback):
 *  - [OnOpenComplaintDialog] — user tapped the "Request adding source" row.
 *  - [OnDismissComplaintDialog] — user dismissed the dialog (back press, outside tap, Cancel).
 *  - [OnSubmitComplaint] — user pressed Submit with a typed URL/description body. The complaint
 *    type is fixed at [me.manga.kira.domain.model.complaint.ComplaintType.SITES_ADD]
 *    (matching legacy `RepoSettingsScreen.kt:217`'s `selectedType = ComplaintType.SITES_ADD`
 *    pinned-type pattern), so the intent carries only the [body] — no type payload.
 *
 * **Phase 7.x.sources.onboardingseed variant** (transient command intent — no state field):
 *  - [OnSeedDefaultLanguage] — onboarding step 3's `LaunchedEffect(userLanguageCode)` fires this
 *    so the rework Sources screen reproduces the legacy auto-seed (enable every source whose
 *    parenthesised language tag matches the user's locale, falling back to `"(EN)"` when none
 *    match). The intent carries the raw locale code; the VM forwards to
 *    [me.manga.kira.domain.usecase.sources.EnableDefaultLanguageSourcesUseCase] which owns
 *    the tag-formatting + EN-fallback policy. The dedup (one-fire per locale change) lives in
 *    the `:ui` composable's `LaunchedEffect` key, NOT in the VM — same posture as the legacy
 *    `SourcesScreen.kt:124-127` `LaunchedEffect(userLanguageCode)`.
 *
 * Contract §6 OCP: a future slice (e.g., `Phase 7.x.sources.infocard` for the "Languages coming
 * soon" info row, or `Phase 7.x.sources.onboardingfinish` for the onboarding "Finish" CTA) is
 * an append here; the VM's exhaustive `when` flags any missing branch at compile time.
 *
 * Contract §6 ISP: each variant carries only the minimal payload — for [OnToggleSource] the
 * full [Source] (cheaper than re-looking-up by api on each click) + the target enabled value;
 * for [OnToggleLanguage] the language string + target enabled value (the per-source fan-out
 * happens in the `:data` impl, not the VM, so the VM doesn't need a `List<Source>` payload);
 * for [OnSubmitComplaint] the body string + the subject string (the type is fixed at
 * SITES_ADD; the subject is the localized display name resolved in `:ui` and threaded down,
 * since the complaint-type display name lives in the `:ui` compose-resources catalog and the
 * VM cannot resolve it — see the [OnSubmitComplaint] member KDoc).
 *
 * **Audit-trail postscript** (Phase 9.x.cluster16.staleKdocSweep.cascade,
 * Task #472, 2026-05-28): four stale citations into §353-retired legacy
 * `:composeApp/.../features/reposettings/ui/screens/RepoSettingsScreen.kt`
 * + §307-retired legacy `:shared/.../onboarding/.../SourcesScreen.kt`
 * appear above:
 *  - Lines 23-25 (Phase 7.x.sources.complaint variants opener): "matching
 *    legacy `RepoSettingsScreen.kt:217`'s `selectedType =
 *    ComplaintType.SITES_ADD` pinned-type pattern". The legacy
 *    `:composeApp/.../features/reposettings/ui/screens/RepoSettingsScreen.kt`
 *    was retired in Phase 9.x.reposettings.legacyui.retire (§353 sweep,
 *    along with the §285 reposettings.swap that re-pointed
 *    `Screen.RepoSettings` to the rework SourcesScreen via §305 +
 *    §307); verified by filesystem check returning zero hits.
 *  - Lines 33-35 (Phase 7.x.sources.onboardingseed variant opener): "the
 *    dedup (one-fire per locale change) lives in the `:ui` composable's
 *    `LaunchedEffect` key, NOT in the VM — same posture as the legacy
 *    `SourcesScreen.kt:124-127` `LaunchedEffect(userLanguageCode)`". The
 *    legacy `:shared/.../onboarding/.../SourcesScreen.kt` was retired in
 *    Phase 9.x.onboarding.legacy_retire (§307 sweep, commit `6c83364`
 *    "delete 5 unreachable legacy onboarding files"); verified by
 *    filesystem check returning zero hits.
 *  - Lines 107-110 (OnSubmitComplaint detail): "mirroring legacy
 *    `RepoSettingsScreen.kt:218-241`'s `complaintViewModel.submit(
 *    SITES_ADD, sitesAddSubject, body, ...)` where `sitesAddSubject` is
 *    the type's display name — Phase 10 i18n lift re-points to the
 *    localized display name". Same §353 retire as above; the i18n lift
 *    rationale stands on its own merits.
 *  - Lines 133-136 (OnSeedDefaultLanguage Transient-no-state-field
 *    detail): "mirroring the legacy `SourcesScreen.kt:124-127`'s
 *    `LaunchedEffect(userLanguageCode)` posture verbatim. Adding a state
 *    field would only re-introduce a second dedup layer with no benefit".
 *    Same §307 retire as above; the dedup-in-`:ui` rationale stands on
 *    its own merits — re-introducing a state field would only duplicate
 *    the dedup mechanism, regardless of whether any legacy peer exists.
 * The rework `ComplaintActionRepository`-backed Request-Source dialog
 * (§282) + `EnableDefaultLanguageSourcesUseCase`-backed onboarding seed
 * (§304) materialised exactly as the intent-surface forecasts on lines
 * 17-19 + 27-31 anticipated — fulfilled predictions. The SRP
 * (`OnToggleSource` / `OnToggleLanguage` / `OnOpenComplaintDialog` /
 * `OnDismissComplaintDialog` / `OnSubmitComplaint` / `OnSeedDefaultLanguage`
 * exhaustive `when` reducer) + OCP (sealed-variant-append) + ISP
 * (minimal-payload-per-intent) rationales all stand on their own merits
 * past the §307 + §353 retires. The rework SourcesIntent remains LIVE
 * as the canonical mutation surface for the rework SourcesViewModel,
 * documented inline above and via the §§241 + §282 + §283 + §284 + §304
 * KDocs. Original §253-era prose preserved verbatim per the audit-trail-
 * preservation convention — the citations are historical record of the
 * design lineage including the parity-mirror-references that were
 * subsequently retired.
 */
sealed interface SourcesIntent : MviIntent {

    /**
     * User flipped a per-source `Switch`. The VM invokes
     * [me.manga.kira.domain.usecase.sources.SetSourceEnabledUseCase] in a coroutine; the
     * upstream `observeSources()` flow re-emits with the source's `isEnabled` flipped.
     *
     * Carries the full [Source] (not just the [Source.api]) so the VM can forward
     * `source.api` to the use case without re-looking up; matches the
     * [me.manga.kira.presentation.updates.UpdatesIntent.OnMarkAsRead] / `OnDeleteEntry`
     * per-entry intent posture.
     */
    data class OnToggleSource(val source: Source, val enabled: Boolean) : SourcesIntent

    /**
     * User flipped a per-language group `Switch`. The VM invokes
     * [me.manga.kira.domain.usecase.sources.SetLanguageEnabledUseCase] in a coroutine; the
     * upstream `observeSources()` flow re-emits after each per-source write commits, so the
     * screen converges on the bulk-result state without VM-side imperative mutation.
     *
     * The per-source fan-out lives inside the `:data` impl, not the VM (see
     * [me.manga.kira.data.repository.SourcesRepositoryImpl.setLanguageEnabled] KDoc) — the
     * VM stays free of repository-shape leakage.
     */
    data class OnToggleLanguage(val language: String, val enabled: Boolean) : SourcesIntent

    /**
     * User tapped the "Request adding source" row. The VM sets
     * [SourcesState.complaintDialogOpen] = `true`; the `:ui` composable observes the flag and
     * renders the Request-Source dialog (single body field — the complaint type is fixed at
     * SITES_ADD so there's no dropdown).
     *
     * No payload: the typed-URL body is LOCAL to the composable (`remember { mutableStateOf
     * ("") }`), matching the [SettingsIntent.OnOpenFeedbackDialog] established posture — content
     * rides along with [OnSubmitComplaint], not separate per-keystroke intents.
     */
    data object OnOpenComplaintDialog : SourcesIntent

    /**
     * User dismissed the Request-Source dialog (back press, outside tap, Cancel button). The
     * VM resets [SourcesState.complaintDialogOpen] to `false`. If
     * [SourcesState.isSubmittingComplaint] is `true`, the VM ignores the dismiss to avoid
     * orphaning the in-flight submission — the `:ui` composable already gates this at the
     * dialog's `properties = DialogProperties(dismissOnBackPress = !isSubmittingComplaint,
     * dismissOnClickOutside = !isSubmittingComplaint)` level, but the VM-side guard is
     * defence-in-depth.
     */
    data object OnDismissComplaintDialog : SourcesIntent

    /**
     * User pressed Submit in the Request-Source dialog. The VM invokes
     * [me.manga.kira.domain.usecase.feedback.SubmitFeedbackUseCase] with the fixed
     * [me.manga.kira.domain.model.complaint.ComplaintType.SITES_ADD] type plus the typed
     * body; on `Result.success` the dialog closes and a confirmation snackbar fires via
     * [SourcesEffect.RequestSubmitted]. On `Result.failure` the dialog stays open and an error
     * snackbar fires (the user retains their typed text — they can retry or copy-out).
     *
     * Payload carries [body] (the URL the user typed) and [subject] (the human-readable
     * complaint subject line).
     *
     * **[subject] — localized display name, not the enum name**: native
     * RepoSettingsScreen submits the complaint with the localized
     * `ComplaintType.SITES_ADD.getDisplayName(context)` ("Add Manga Site" /
     * `add_manga_site`) as the subject, which is what surfaces to whoever triages requests in
     * the admin tool. The subject must therefore be a localized string resolved in `:ui`
     * (where `stringResource` is available) and threaded down through this intent — the VM
     * cannot resolve it because the complaint type's display name lives in the `:ui`
     * compose-resources catalog. This supersedes the former VM-side `ComplaintType.SITES_ADD.
     * name` ("SITES_ADD") subject, which was a data divergence from the native source of
     * truth (the backend would have recorded the raw enum constant instead of the readable
     * label).
     *
     * In-flight protection: [SourcesState.isSubmittingComplaint] is set `true` immediately
     * and reset on the use case's `Result<Unit>` completion. The Submit button is disabled
     * and the dismiss path is gated while the flag is set.
     */
    data class OnSubmitComplaint(val body: String, val subject: String) : SourcesIntent

    /**
     * Onboarding step 3 fired a "seed default language" command — enable every source whose
     * parenthesised language tag matches [languageTag] (case-insensitive — the tag is
     * uppercased + parenthesised inside the use case to match the legacy `saveSources` seed
     * convention), falling back to `"(EN)"` when no native sources exist for that locale.
     *
     * Phase 7.x.sources.onboardingseed. The VM invokes
     * [me.manga.kira.domain.usecase.sources.EnableDefaultLanguageSourcesUseCase] in a
     * coroutine; the use case owns the tag-formatting + EN-fallback policy, the repository
     * owns the snapshot + fan-out mechanism. The upstream `observeSources()` flow re-emits
     * after each per-source Room write commits, so the screen converges on the bulk result
     * without VM-side imperative mutation — same posture as [OnToggleLanguage].
     *
     * **Transient — no state field**: this is a "fire-and-forget seed" command, not a user
     * action with a UI Switch attached. The state has no `seedFired: Boolean` field — the
     * dedup (one-fire per locale change) lives in the `:ui` composable's
     * `LaunchedEffect(onboardingLanguageTag)` key, mirroring the legacy
     * `SourcesScreen.kt:124-127`'s `LaunchedEffect(userLanguageCode)` posture verbatim. Adding
     * a state field would only re-introduce a second dedup layer with no benefit.
     *
     * **Payload — raw locale code, not the pre-formatted tag**: the VM forwards the raw
     * `languageTag` to the use case as-is; the use case calls
     * `.ifBlank { "en" }.uppercase()` + wraps in parens. Keeping the formatting policy in the
     * use case (not the intent) means a future onboarding revision (e.g., a different
     * tag format) is a use-case-only change — the intent payload stays shape-stable.
     */
    data class OnSeedDefaultLanguage(val languageTag: String) : SourcesIntent
}
