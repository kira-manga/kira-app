package me.manga.kira.domain.repository

import me.manga.kira.domain.model.whatsnew.WhatsNewFeature

/**
 * Contract for the rework What's New surface's data source.
 *
 * Phase 7.x.whatsnew (foundation). Backed in `:data` by
 * [me.manga.kira.data.repository.WhatsNewRepositoryImpl], which is a strangler-fig delegate
 * over FOUR `:shared` legacy facades: `WhatsNewRemoteDataSource` (Ktor JSON fetcher),
 * `SharedPrefsHelper` (last-seen prefs), `AppVersionProvider` (current `versionName` for the
 * mark-seen write), and the top-level `getDefaultFeatures()` fun (currently returns
 * `emptyList()`). Highest fan-out into `:shared` of any rework `:data` impl to date — justified
 * by the legacy `WhatsNewViewModel` owning the SAME collaboration, which this slice ports.
 *
 * **Two-method surface — ISP §6**. The foundation slice ships exactly two concerns: read the
 * features list, write the mark-seen prefs. Should-show gating (a `Flow<Boolean>` keyed on
 * version-name comparison) is DEFERRED to a follow-on sub-slice
 * (`Phase 7.x.whatsnew.gate`) — see [PLAN_whatsnew.md] §Deferrals. Adding a `observeShouldShow():
 * Flow<Boolean>` method later is an OCP-compliant interface extension; existing impl gets a
 * default delegating to a synchronous version-name comparison if needed.
 *
 * **`suspend fun getFeatures()` not `Flow<List<WhatsNewFeature>>`** — the feature list is static
 * for the running process (the remote endpoint isn't observed for changes; the local default
 * list is a top-level fun). A one-shot `suspend` is the precise shape. Same posture as
 * [AboutRepository.getMetadata] (Phase 7.x.about) and [IsAdultContentUseCase] (Phase 6.3.4).
 *
 * **`suspend fun markSeen()` with no params** — the impl reads the current version from
 * `AppVersionProvider` at write time, not from a caller-supplied param. This keeps the caller
 * (`WhatsNewViewModel`) decoupled from the legacy version-string contract. Equivalent
 * idempotency: two consecutive `markSeen()` calls write the same value pair.
 *
 * **Why no `Result<List<WhatsNewFeature>>` wrapper** — the legacy data source already wraps the
 * Ktor call in a `Result<WhatsNewResponse>` internally; the `:data` impl unpacks that and falls
 * back to `getDefaultFeatures()` (empty list) on any failure. The boundary surfaces a
 * `List<WhatsNewFeature>` directly — empty list IS the "no features" signal. Future error
 * surfacing (a `WhatsNewEffect.ShowError(...)` variant) would gain a `getFeatures(): AppResult<...>`
 * method alongside this one.
 *
 * Contract §6 DIP — `:presentation`'s [me.manga.kira.presentation.whatsnew.WhatsNewViewModel]
 * depends on this `:domain` interface (via [me.manga.kira.domain.usecase.whatsnew.GetWhatsNewFeaturesUseCase]
 * and [me.manga.kira.domain.usecase.whatsnew.MarkWhatsNewSeenUseCase]), not on the `:data`
 * impl or the legacy `:shared` facades. Test substitution + future `:platform` rewire (Phase
 * 8.z) both stay open without touching `:presentation`.
 *
 * **Audit-trail postscript** (Phase 9.x.cluster140.staleKdocSweep.cascade,
 * Task #596, 2026-05-28): classified as follows after recursive symbol
 * verification (one-hundred-and-forty-first sibling of the cluster57-139
 * sweep — fourth file of the wave-25 second-cluster 5-leaf-repository
 * batch alongside PageProgressRepository plus ReadingStatisticsRepository
 * plus AboutRepository):
 *  (a) "Contract-for-the-rework-What-s-New-surface-data-source + Phase-
 *  7.x.whatsnew-foundation + Backed-in-:data-by-WhatsNewRepositoryImpl-
 *  which-is-a-strangler-fig-delegate-over-FOUR-:shared-legacy-facades-
 *  WhatsNewRemoteDataSource-SharedPrefsHelper-AppVersionProvider-plus-
 *  the-top-level-getDefaultFeatures-fun + Highest-fan-out-into-:shared-
 *  of-any-rework-:data-impl-to-date-justified-by-the-legacy-WhatsNew-
 *  ViewModel-owning-the-SAME-collaboration-which-this-slice-ports +
 *  Two-method-surface-ISP-§6 + The-foundation-slice-ships-exactly-two-
 *  concerns-read-the-features-list-write-the-mark-seen-prefs + Should-
 *  show-gating-a-Flow-Boolean-keyed-on-version-name-comparison-is-
 *  DEFERRED-to-a-follow-on-sub-slice-Phase-7.x.whatsnew.gate +
 *  Adding-a-observeShouldShow-Flow-Boolean-method-later-is-an-OCP-
 *  compliant-interface-extension + suspend-fun-getFeatures-not-Flow-
 *  List-WhatsNewFeature + The-feature-list-is-static-for-the-running-
 *  process + suspend-fun-markSeen-with-no-params + The-impl-reads-the-
 *  current-version-from-AppVersionProvider-at-write-time-not-from-a-
 *  caller-supplied-param + Equivalent-idempotency-two-consecutive-
 *  markSeen-calls-write-the-same-value-pair" — LIVE-NOT-STALE plus
 *  FULFILLED-PREDICTION plus FORECAST-NOT-YET-FULFILLED-(Phase-7.x.
 *  whatsnew.gate-observeShouldShow-method plus Phase-7.x.whatsnew.i18n-
 *  language-passthrough). Verified via recursive grep: WhatsNew-
 *  Repository is consumed by GetWhatsNewFeaturesUseCase plus MarkWhats-
 *  NewSeenUseCase (the :domain callers) plus WhatsNewRepositoryImpl
 *  (the :data 4-shared-facade-delegate impl) plus WhatsNewReworkModule
 *  plus WhatsNewIntent plus WhatsNewScreenRoute. The interface still
 *  declares exactly two methods — `suspend fun getFeatures()` plus
 *  `suspend fun markSeen()`. No `observeShouldShow` has landed (gate
 *  forecast still open). Language is still hardcoded to "en" per the
 *  i18n deferral. The "highest fan-out into :shared" claim still holds
 *  as a §250 LIVE-tagged shadow-legacy facade dependency — the
 *  strangler-fig wire-format-compat key set + `:shared` facades remain
 *  reachable from :data.
 *  (b) "Why-no-Result-List-WhatsNewFeature-wrapper + The-legacy-data-
 *  source-already-wraps-the-Ktor-call-in-a-Result-WhatsNewResponse-
 *  internally + The-:data-impl-unpacks-that-and-falls-back-to-
 *  getDefaultFeatures-empty-list-on-any-failure + The-boundary-surfaces-
 *  a-List-WhatsNewFeature-directly-empty-list-IS-the-no-features-signal
 *  + Future-error-surfacing-a-WhatsNewEffect.ShowError-variant-would-
 *  gain-a-getFeatures-AppResult-method-alongside-this-one + Contract-
 *  §6-DIP + :presentation-WhatsNewViewModel-depends-on-this-:domain-
 *  interface-via-GetWhatsNewFeaturesUseCase-and-MarkWhatsNewSeenUseCase-
 *  not-on-the-:data-impl-or-the-legacy-:shared-facades + Test-
 *  substitution-plus-future-:platform-rewire-Phase-8.z-both-stay-open-
 *  without-touching-:presentation + Round-trips-with-the-legacy-
 *  WhatsNewViewModel.markWhatsNewAsSeen-both-surfaces-write-to-the-
 *  SAME-prefs-keys" — LIVE-NOT-STALE plus FULFILLED-PREDICTION plus
 *  FORECAST-NOT-YET-FULFILLED-(future-getFeatures-AppResult-error-
 *  surface plus Phase-8.z-:platform-rewire). Verified: WhatsNewView-
 *  Model imports only the two use cases — no :data import + no :shared
 *  facade reach. The empty-list-as-no-features-signal posture holds —
 *  no AppResult/Result has crept in. Both rework + legacy WhatsNew
 *  routes write to the SAME `whats_new_last_shown_version_name` plus
 *  `whats_new_last_shown_timestamp` SharedPrefsHelper keys per the
 *  :data impl's cluster23 §479 postscript — cross-strangler-fig wire-
 *  format-compat key set holds.
 *  Two classifications STAND on their own merits. Original Phase 7.x.
 *  whatsnew-foundation-era prose preserved verbatim per the audit-trail-
 *  preservation convention.
 */
interface WhatsNewRepository {

    /**
     * Returns the list of What's New features for the current app version.
     *
     * Implementation note: the foundation `:data` impl hardcodes the language to `"en"` (the
     * legacy reads `DataStoreHelper.languageFlow.first()` — deferred to `Phase 7.x.whatsnew.i18n`).
     * On remote failure or empty response, falls back to the legacy top-level
     * `getDefaultFeatures()` (which currently returns `emptyList()`).
     */
    suspend fun getFeatures(): List<WhatsNewFeature>

    /**
     * Marks the current app version as "seen" — writes the current `versionName` (from the
     * legacy `AppVersionProvider`) and the current epoch milliseconds to the legacy
     * `SharedPrefsHelper` keys (`"whats_new_last_shown_version_name"` +
     * `"whats_new_last_shown_timestamp"`). Idempotent.
     *
     * Round-trips with the legacy `WhatsNewViewModel.markWhatsNewAsSeen()` — both surfaces write
     * to the SAME prefs keys, so marking-seen on either route is visible to the other.
     */
    suspend fun markSeen()
}
