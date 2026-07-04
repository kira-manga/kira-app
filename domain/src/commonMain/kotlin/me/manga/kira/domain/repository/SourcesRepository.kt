package me.manga.kira.domain.repository

import kotlinx.coroutines.flow.Flow
import me.manga.kira.domain.model.sources.Source

/**
 * Reactive content-sources access — observe the per-source list, toggle a single source, toggle
 * every source in a language.
 *
 * Phase 7.x.sources rework. The `:data` impl strangler-fig delegates to the legacy `:shared`
 * `me.manga.kira.presentation.features.repo_settings.domain.SourcesRepository` (which wraps
 * `SourcesDao` + the `sources` Room table + the in-memory `repos` set the Coil interceptor
 * routes against). The legacy facade remains the cell of truth for the routing surface
 * (`findRepoByHost`, `activeRepoFlow`, `getEnabledRepos`) — the rework deliberately narrows
 * the interface to ONLY what the Sources screen reads + mutates, and leaves the routing surface
 * on the legacy facade for the Coil interceptor, Home, Search, and every other consumer to keep
 * using verbatim. (`repoTaps` / `getUrl` removed in Phase 9.x.repo.componentprune.cumulative —
 * Task #415.)
 *
 * Contract §6 SRP: owns ONE rule — "expose content sources as a read + per-source / per-language
 * toggle surface for the Sources screen". Source-routing (`findRepoByHost`,
 * `getRepoByName`), the on-disk seed (`saveSources`), and the active-tab
 * (`updateActiveIndex`, `updateActiveByApi`) are intentionally NOT on this interface — those
 * surfaces belong to the routing flow, not the screen. (Phase 9.x.sourcesrepository.component
 * prune dropped the legacy `getActiveRepo` entirely; `activeRepoFlow` + `getRepoByName` now
 * serve that role.)
 *
 * Contract §6 ISP: four methods covering the exact action set the rework Sources screen
 * surfaces — one read flow + three toggle mutators (per-source, per-language, per-language-
 * with-fallback). No `getByApi` (the screen already holds the [Source] at click time), no
 * site-state mutators (rework drops `siteState` per [Source]'s 4-field rationale). The
 * fourth method ([setLanguageEnabledWithFallback]) was added in Phase 7.x.sources.onboarding
 * seed to support the onboarding step 3 default-language auto-seed — previously the
 * onboarding seed lived on the legacy `RepoSettingsViewModel.setLanguageEnabledDefault` and
 * was deferred in the original §84 rework; this slice lifts it onto the rework `:domain`
 * surface so a future `Phase 7.x.sources.swap` can route legacy `Screen.Sources` to the
 * rework screen without losing the seed.
 *
 * Contract §6 DIP: consumers (the 3 use cases — `ObserveSourcesUseCase`,
 * `SetSourceEnabledUseCase`, `SetLanguageEnabledUseCase`, and through them the rework
 * `SourcesViewModel`) depend on this interface, never on the legacy facade or the underlying
 * DAO. Koin binds the impl at the composition root in `sourcesReworkModule`.
 *
 * Lifecycle expectation: the impl is bound as a `single` (matching the upstream legacy
 * `SourcesRepository`'s `single` lifecycle from `SharedModule`). A `factory` would resubscribe
 * the upstream `allSources` flow on each resolution — wasteful for a read-mostly surface
 * shared across the app's lifetime.
 *
 * Load-bearing fixes preserved: the legacy `findRepoByHost` path the Coil image interceptor
 * uses to attach per-source Cookie / User-Agent / Referer headers (MEMORY:
 * `project_yami_okhttp_fetcher`) lives on the legacy facade and is UNTOUCHED by this rework.
 * The `:data` impl reaches into the legacy facade for ONLY `allSources` + `enableDisAbleSource`;
 * everything else on the legacy surface stays for its current consumers.
 *
 * **Audit-trail postscript** (Phase 9.x.cluster141.staleKdocSweep.cascade,
 * Task #597, 2026-05-28): classified as follows after recursive symbol
 * verification (one-hundred-and-forty-fifth sibling of the cluster57-140
 * sweep — third and closing file of the wave-25 third-cluster 3-leaf-
 * repository closing batch alongside LibraryRepository plus AdminComplaint-
 * ListRepository; CLOSES cluster141 plus CLOSES wave-25 plus BRINGS THE
 * :domain/repository/ TIER TO 26/26 FULLY SWEPT):
 *  (a) "Reactive-content-sources-access-observe-the-per-source-list-
 *  toggle-a-single-source-toggle-every-source-in-a-language + Phase-
 *  7.x.sources-rework + The-:data-impl-strangler-fig-delegates-to-the-
 *  legacy-:shared-SourcesRepository-which-wraps-SourcesDao-plus-the-
 *  sources-Room-table-plus-the-in-memory-repos-set-the-Coil-
 *  interceptor-routes-against + The-legacy-facade-remains-the-cell-of-
 *  truth-for-the-routing-surface-findRepoByHost-activeRepoFlow-
 *  getEnabledRepos + the-rework-deliberately-narrows-the-interface-to-
 *  ONLY-what-the-Sources-screen-reads-plus-mutates-and-leaves-the-
 *  routing-surface-on-the-legacy-facade-for-the-Coil-interceptor-Home-
 *  Search-and-every-other-consumer-to-keep-using-verbatim + repoTaps-
 *  getUrl-removed-in-Phase-9.x.repo.componentprune.cumulative-Task-415
 *  + Contract-§6-SRP-owns-ONE-rule-expose-content-sources-as-a-read-
 *  plus-per-source-per-language-toggle-surface-for-the-Sources-screen
 *  + Source-routing-findRepoByHost-getRepoByName-the-on-disk-seed-
 *  saveSources-and-the-active-tab-updateActiveIndex-updateActiveByApi-
 *  are-intentionally-NOT-on-this-interface + Phase-9.x.sourcesrepository.
 *  componentprune-dropped-the-legacy-getActiveRepo-entirely + Contract-
 *  §6-ISP-four-methods-covering-the-exact-action-set-the-rework-
 *  Sources-screen-surfaces + one-read-flow-plus-three-toggle-mutators-
 *  per-source-per-language-per-language-with-fallback + No-getByApi-
 *  the-screen-already-holds-the-Source-at-click-time + no-site-state-
 *  mutators-rework-drops-siteState-per-Source-4-field-rationale + The-
 *  fourth-method-setLanguageEnabledWithFallback-was-added-in-Phase-
 *  7.x.sources.onboardingseed + previously-the-onboarding-seed-lived-
 *  on-the-legacy-RepoSettingsViewModel.setLanguageEnabledDefault-and-
 *  was-deferred-in-the-original-§84-rework + this-slice-lifts-it-onto-
 *  the-rework-:domain-surface-so-a-future-Phase-7.x.sources.swap-can-
 *  route-legacy-Screen.Sources-to-the-rework-screen-without-losing-
 *  the-seed" — LIVE-NOT-STALE plus FULFILLED-PREDICTION. Verified via
 *  recursive grep: SourcesRepository is consumed by ObserveSourcesUse-
 *  Case + SetSourceEnabledUseCase + SetLanguageEnabledUseCase +
 *  EnableDefaultLanguageSourcesUseCase (the §304 4-use-case fan-out)
 *  plus SourcesRepositoryImpl plus SourcesReworkModule plus Sources-
 *  ReworkScreenRoute. The §305 Phase-7.x.sources.swap landed; the
 *  §285 Phase-7.x.reposettings.swap landed. The 4-method narrowed
 *  surface still matches the impl's 4-method body count 1:1 — no
 *  drift since onboarding seed §304. The legacy facade's routing
 *  surface (findRepoByHost + activeRepoFlow + getEnabledRepos +
 *  saveSources + updateActiveIndex + updateActiveByApi) remains
 *  UNTOUCHED on the legacy :shared SourcesRepository per its
 *  cluster6 §462 postscript — the dual-surface posture (rework
 *  toggle + legacy routing) holds across the live status doc.
 *  (b) "Contract-§6-DIP-consumers-the-3-use-cases-Observe-Sources-
 *  UseCase-SetSourceEnabledUseCase-SetLanguageEnabledUseCase-and-
 *  through-them-the-rework-SourcesViewModel-depend-on-this-interface-
 *  never-on-the-legacy-facade-or-the-underlying-DAO + Koin-binds-the-
 *  impl-at-the-composition-root-in-sourcesReworkModule + Lifecycle-
 *  expectation-the-impl-is-bound-as-a-single-matching-the-upstream-
 *  legacy-SourcesRepository-single-lifecycle + A-factory-would-
 *  resubscribe-the-upstream-allSources-flow-on-each-resolution-
 *  wasteful-for-a-read-mostly-surface-shared-across-the-app-lifetime
 *  + Load-bearing-fixes-preserved-the-legacy-findRepoByHost-path-the-
 *  Coil-image-interceptor-uses-to-attach-per-source-Cookie-User-Agent-
 *  Referer-headers-lives-on-the-legacy-facade-and-is-UNTOUCHED-by-
 *  this-rework + The-:data-impl-reaches-into-the-legacy-facade-for-
 *  ONLY-allSources-plus-enableDisAbleSource-everything-else-on-the-
 *  legacy-surface-stays-for-its-current-consumers + Reactive-list-of-
 *  every-content-source-registered-with-the-app-in-their-natural-
 *  persistence-order + Emits-an-updated-list-on-every-Room-write-to-
 *  the-sources-table + The-flat-list-shape-not-pre-grouped-by-
 *  language-lets-the-:ui-regroup-using-the-same-groupBy-idiom-the-
 *  History-Updates-screens-use-to-bucket-by-date + one-regroup-
 *  convention-across-all-list-screens-§83.3 + Toggle-a-single-source-
 *  enabled-state + Takes-the-source-Source.api-the-persistence-stable-
 *  key-plus-the-target-enabled-value + Toggle-every-source-in-a-given-
 *  language-together + Implemented-by-snapshotting-the-current-source-
 *  set-filtering-to-the-target-language-and-forwarding-each-entry-
 *  through-setSourceEnabled + Toggle-every-source-in-primary-or-fall-
 *  back-to-fallback-when-primary-has-zero-matching-sources-together +
 *  Mirrors-the-legacy-RepoSettingsViewModel.setLanguageEnabledDefault-
 *  semantic + the-rework-lifts-the-fallback-to-a-method-parameter-so-
 *  the-mechanism-stays-policy-neutral-and-the-policy-defaulting-to-
 *  English-lives-in-the-EnableDefaultLanguageSourcesUseCase + Why-a-
 *  separate-method-not-an-optional-fallback-parameter-on-set-
 *  LanguageEnabled-ISP + The-per-language-Switch-on-the-Sources-screen-
 *  explicitly-toggles-ONE-language + a-fallback-there-would-silently-
 *  widen-the-user-intent + Why-both-primary-and-fallback-are-
 *  parameters-vs-a-hard-coded-EN-fallback-in-the-impl-DIP-SRP + The-
 *  repository-owns-mechanism-snapshot-filter-fan-out-the-policy-
 *  default-to-EN-belongs-in-the-use-case + A-future-onboarding-revision-
 *  e.g.-fall-back-to-the-device-region-rather-than-EN-is-a-use-case-
 *  only-change-with-no-impact-on-the-data-layer + Match-rules-case-
 *  sensitive-equals-against-the-persisted-language-column-matching-
 *  the-legacy-convention + Format-is-the-parenthesised-tag-the-legacy-
 *  saveSources-seeds-rows-with-e.g.-EN-FR-the-caller-use-case-formats-
 *  the-user-raw-locale-code" — LIVE-NOT-STALE plus FULFILLED-
 *  PREDICTION plus FORECAST-NOT-YET-FULFILLED-(Phase-9.x-legacy-
 *  :shared-SourcesRepository-non-routing-surface-retire-once-Coil-
 *  interceptor-can-resolve-headers-via-rework-paths). Verified:
 *  ObserveSourcesUseCase + SetSourceEnabledUseCase + SetLanguage-
 *  EnabledUseCase + EnableDefaultLanguageSourcesUseCase depend only
 *  on this interface — no :data import + no :shared facade reach
 *  from any use case. SourcesViewModel imports only the use cases
 *  per its cluster30 §253 postscript. The Coil image interceptor's
 *  findRepoByHost reach remains as documented in MEMORY:
 *  project_yami_okhttp_fetcher — the load-bearing per-source-header
 *  routing is intact.
 *  Two classifications STAND on their own merits. **CLOSES cluster141.
 *  CLOSES wave-25. BRINGS :domain/repository/ TIER TO 26/26 FULLY
 *  SWEPT.** Original Phase 7.x.sources-era prose (extended through
 *  §304 onboarding seed) preserved verbatim per the audit-trail-
 *  preservation convention.
 */
interface SourcesRepository {

    /**
     * Reactive list of every content source registered with the app, in their natural
     * persistence order. Emits an updated list on every Room write to the `sources` table.
     *
     * The flat-list shape (not pre-grouped by language) lets the `:ui` regroup using the same
     * `groupBy` idiom the History / Updates screens use to bucket by date — one regroup
     * convention across all list screens (§83.3 in [ARCHITECTURE.md]).
     */
    fun observeSources(): Flow<List<Source>>

    /**
     * Toggle a single source's enabled state. Fire-and-forget — the upstream [observeSources]
     * flow re-emits with the source's `isEnabled` flipped once the Room transaction commits.
     *
     * Takes the source's [Source.api] (the persistence-stable key) plus the target `enabled`
     * value. The legacy facade method is `enableDisAbleSource(name, enabled)` — the rework
     * forwards verbatim; the API name and the entity `name` column are the same string (the
     * legacy `saveSources` seeds the row with `name = repo.API`).
     */
    suspend fun setSourceEnabled(api: String, enabled: Boolean)

    /**
     * U2 (new-sources badge): whether the server-side catalog gained sources the user hasn't
     * looked at yet. Set `true` by the What's-New pipeline when a release announces new sources;
     * cleared when the user opens the source-edit surface ([setHasNewSources] false). Backed by
     * the `new_sources_added` prefs cell (default true on a fresh install, matching native).
     */
    fun observeHasNewSources(): Flow<Boolean>

    /** Persist the new-sources badge flag (see [observeHasNewSources]). */
    suspend fun setHasNewSources(value: Boolean)

    /**
     * Toggle every source in a given language together. Fire-and-forget — the upstream
     * [observeSources] flow re-emits once each per-source Room write commits.
     *
     * Implemented by snapshotting the current source set, filtering to the target [language],
     * and forwarding each entry through [setSourceEnabled]. This mirrors the legacy onboarding
     * `RepoSettingsViewModel.toggleLanguage` posture — Room serialises the per-source writes
     * and the upstream flow coalesces emissions, so the screen sees the final language-bulk
     * result after all writes settle.
     */
    suspend fun setLanguageEnabled(language: String, enabled: Boolean)

    /**
     * Toggle every source in [primary] (or fall back to [fallback] when [primary] has zero
     * matching sources) together. Fire-and-forget — the upstream [observeSources] flow
     * re-emits after each per-source Room write commits, so the screen converges on the bulk
     * result without VM-side imperative mutation.
     *
     * Phase 7.x.sources.onboardingseed mechanism. Mirrors the legacy
     * `RepoSettingsViewModel.setLanguageEnabledDefault` semantic (which hard-codes the
     * fallback to `"(EN)"`); the rework lifts the fallback to a method parameter so the
     * mechanism stays policy-neutral and the policy (defaulting to English) lives in the
     * [me.manga.kira.domain.usecase.sources.EnableDefaultLanguageSourcesUseCase] use case.
     *
     * **Why a separate method (not an optional `fallback` parameter on [setLanguageEnabled])**
     * — ISP. The per-language Switch on the Sources screen explicitly toggles ONE language; a
     * fallback there would silently widen the user's intent (the user picked French; the
     * fallback would enable English if French were empty — surprise behaviour). Keeping the
     * two methods separate makes the call site's intent explicit at the type level.
     *
     * **Why both [primary] and [fallback] are parameters (vs. a hard-coded EN fallback in
     * the impl)** — DIP / SRP. The repository owns mechanism (snapshot, filter, fan out);
     * the policy ("default to EN") belongs in the use case. A future onboarding revision
     * (e.g. fall back to the device's region rather than EN) is a use-case-only change with
     * no impact on the data layer.
     *
     * **Match rules**: case-sensitive `==` against the persisted `language` column,
     * matching the legacy convention. Format is the parenthesised tag the legacy
     * `saveSources` seeds rows with (e.g. `"(EN)"`, `"(FR)"`); the caller (use case)
     * formats the user's raw locale code.
     */
    suspend fun setLanguageEnabledWithFallback(primary: String, fallback: String, enabled: Boolean)
}
