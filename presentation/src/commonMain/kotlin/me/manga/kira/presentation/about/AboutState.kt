package me.manga.kira.presentation.about

import me.manga.kira.presentation.mvi.MviState

/**
 * Immutable view-state for the rework About screen.
 *
 * Phase 7.x.about. Carries the three fields the :ui composable renders:
 *
 * - [isLoading] — `true` between [AboutViewModel.init] and the first
 *   [me.manga.kira.domain.usecase.about.GetAppMetadataUseCase] resolution. The legacy
 *   `:shared` `AppVersionProvider` resolves synchronously (two property reads, no IO), so
 *   the loading window is effectively zero frames on a real device — the flag exists for
 *   forward-compat with a future async metadata source (e.g., remote build channel lookup)
 *   without forcing a state-shape change.
 * - [versionName] — user-facing version string (e.g., `"1.2.3"`), defaults to empty string
 *   while loading. Non-null per the legacy provider's structural infallibility contract
 *   (substitutes `"unknown"` on platform read failure).
 * - [packageName] — reverse-DNS app id (e.g., `"me.manga.kira"`), defaults to empty string
 *   while loading. Used by the "Check for update" + "Rate our app" rows to dispatch
 *   [AboutEffect.OpenPlayStorePage].
 *
 * **Why a single state, not a sealed `Loading`/`Loaded` ADT** — same posture as
 * [me.manga.kira.presentation.statistics.StatisticsState] / [me.manga.kira.presentation.theme.ThemeState]:
 * the loaded-state is a strict superset of the loading state (all empty strings → filled
 * strings), so a single data class with `isLoading: Boolean` keeps the [AboutScreen]
 * recomposition logic simple (one Card layout that fills in field values as they arrive).
 * A sealed ADT would force the :ui to switch on the type and duplicate the row layout — no
 * gain.
 *
 * **Strict MVI Contract §17**: pure value type, immutable, no banned features (`Any`, `!!`,
 * `lateinit`). All fields are non-null with sensible defaults.
 *
 * **Audit-trail postscript** (Phase 9.x.cluster106.staleKdocSweep.cascade,
 * Task #562, 2026-05-28): the file-scope state-shape manifest above is
 * classified as follows after recursive symbol verification across the
 * KMP graph (forty-sixth sibling of the cluster57-105 sweep — sibling
 * of cluster106 LanguageIntent.kt plus LanguageState.kt plus Language-
 * ViewModel.kt plus AboutViewModel.kt):
 *  (a) "Three fields the :ui composable renders — isLoading plus
 *  versionName plus packageName" — LIVE-NOT-STALE. L34-38 data-class
 *  shape verbatim — three `val`-only properties: `isLoading: Boolean =
 *  true` plus `versionName: String = ""` plus `packageName: String = ""`.
 *  (b) "Forward-compat isLoading rationale — flag exists for a future
 *  async metadata source even though the legacy `AppVersionProvider`
 *  resolves synchronously (two property reads, no IO)" — LIVE-NOT-STALE.
 *  AboutViewModel.kt L67-78 init block dispatches `viewModelScope.launch
 *  { val metadata = getAppMetadata(); updateState { ... isLoading = false
 *  ... } }` — the loading-window-effectively-zero-frames posture
 *  preserved; flag is forward-compat scaffolding for the GetAppMetadata-
 *  UseCase suspend return type.
 *  (c) "Why a single state, not a sealed `Loading`/`Loaded` ADT — same
 *  posture as [StatisticsState] / [ThemeState]" — LIVE-NOT-STALE. Peer
 *  cross-ref to StatisticsState single-data-class shape verified at
 *  cluster102 sweep (Task #558); ThemeState single-data-class shape
 *  verified at cluster105 sibling sweep (Task #561). The loaded-state
 *  strict-superset rationale (empty strings to filled strings) preserved
 *  verbatim.
 *  Three classifications STAND on their own merits as a faithful
 *  AboutState manifest. Original Phase 7.x.about-era prose preserved
 *  verbatim per the audit-trail-preservation convention.
 */
data class AboutState(
    val isLoading: Boolean = true,
    val versionName: String = "",
    val packageName: String = "",
) : MviState
