package me.manga.kira.presentation.features.settings.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import me.manga.kira.presentation.features.settings.domain.SettingsRepository

/**
 * Migration note (Phase 9.7):
 * - Dropped `@HiltViewModel` + `@Inject` + `@ApplicationContext` (Koin wires via
 *   `viewModel { ... }` in SharedModule).
 * - `AndroidViewModel(context.applicationContext as Application)` -> plain `ViewModel()` —
 *   the `Application` was never used by the body.
 *
 * Phase 9.x.settingsvm.componentprune (Task #397): dropped 10 orphan members surfaced by an
 * exhaustive 3-pass reacher-chain audit (`settingsViewModel.X` receiver-anchored + bare
 * `\bX\b` word-boundary + `::X` method-ref) covering the entire source tree. The only
 * external consumer of this legacy VM is `composeApp/.../App.kt:226-228`, which reads the
 * three theme StateFlows (`followSystem` / `darkMode` / `pureBlack`) to bootstrap
 * `KiraMangaTheme`. Every other property/method on the legacy VM had ZERO external reach
 * because Phase 7.x.settings.swap (Task #301) route-swapped the legacy Settings screen to
 * the rework — the rework `:presentation/.../settings/SettingsViewModel` now owns all
 * toggle/clear/cache plumbing (see `:presentation` for the rework MVI surface).
 * Removed (all independently-orphan):
 *  - `val downloadedOnly = settingsRepo.downloadedOnlyFlow` — no reacher.
 *  - `val incognito = settingsRepo.incognitoFlow` — no reacher.
 *  - `private val _cacheSize: MutableStateFlow<String>` + `val cacheSize: StateFlow<String>` —
 *    no external reacher; the rework `SettingsViewModel` derives `cacheSize` via
 *    `SettingsRepositoryImpl.observeSettings()`.
 *  - `init { viewModelScope.launch(IODispatcher) { updateCacheSize() } }` — was only used to
 *    bootstrap `_cacheSize`; orphan after the cacheSize drop.
 *  - `fun toggleDarkMode(on)` / `fun togglePureBlack(on)` / `fun toggleFollowSystem(on)` —
 *    the rework Settings screen invokes `SetAppThemeUseCase` / `SetPureBlackUseCase` /
 *    `UpdateSettingsToggleUseCase`, which all reach the legacy repo's setter via the
 *    `:data/ThemeRepositoryImpl` and `:data/SettingsRepositoryImpl` strangler-figs (never
 *    via this VM).
 *  - `fun setDownloadedOnly(enabled)` / `fun setIncognito(enabled)` / `fun setFollowSystem(enabled)` —
 *    rework `UpdateSettingsToggleUseCase` is the LIVE write path.
 *  - `fun clearLargeCache()` — rework `ClearCacheUseCase` is the LIVE clear path.
 *  - `private fun updateCacheSize()` — internal helper, orphan after `_cacheSize` /
 *    `clearLargeCache` drop.
 * LIVE members preserved (verified by exhaustive reacher-chain audit):
 *  - `val darkMode: StateFlow<Boolean>` — `App.kt:227`.
 *  - `val pureBlack: StateFlow<Boolean>` — `App.kt:228`.
 *  - `val followSystem: StateFlow<Boolean>` — `App.kt:226`.
 *
 * Imports trimmed in the same slice: `kotlinx.coroutines.flow.MutableStateFlow`,
 * `kotlinx.coroutines.launch`, `me.manga.kira.core.concurrency.IODispatcher` —
 * unused after the orphan drops. `viewModelScope` import retained for `stateIn`.
 */
class SettingsViewModel(
    private val settingsRepo: SettingsRepository,
) : ViewModel() {

    val darkMode: StateFlow<Boolean> = settingsRepo.darkModeFlow
        .stateIn(viewModelScope, SharingStarted.Eagerly, settingsRepo.isDarkMode())

    val pureBlack: StateFlow<Boolean> = settingsRepo.pureBlackFlow
        .stateIn(viewModelScope, SharingStarted.Eagerly, settingsRepo.isPureBlack())

    val followSystem: StateFlow<Boolean> = settingsRepo.followSystemFlow
        .stateIn(viewModelScope, SharingStarted.Eagerly, settingsRepo.isFollowSystem())
}

/*
 * Audit-trail postscript (Phase 9.x.cluster210.staleKdocSweep.cascade, Task #666, 2026-05-29)
 * --------------------------------------------------------------------------------------------
 * Cluster210 leaf 1/4 — :shared/settings/ui/viewmodel/ tier SINGLE-LEAF, sibling 386. Cumulative
 * §253-postscript count = 111 leaves with this commit.
 *
 * File-shape note: 65-line class — `SettingsViewModel` with 1 ctor dep (settingsRepo:
 * SettingsRepository). Surfaces 3 LIVE theme-bootstrap StateFlow fields after Task #397
 * componentprune: darkMode + pureBlack + followSystem (all eagerly-stated). 43-line class-level
 * KDoc carrying Phase 9.7 migration prose + Task #397 componentprune lineage (10 dropped members).
 *
 * Body-level deltas (cluster57+ taxonomy):
 *
 *   • LIVE-NOT-STALE — narrow-purpose theme-bootstrap SOURCE — direct consumer (verified via the
 *     1-hit App.kt:226-228 anchor cited in the prior KDoc head):
 *       1. App.kt (:composeApp/) — reads all 3 LIVE StateFlows to bootstrap `KiraMangaTheme` at
 *          composeApp init. The legacy Settings screen consumer was retired by Task #301
 *          (Phase 7.x.settings.swap) — post-swap this VM survives as a thin theme-bootstrap
 *          adapter, NOT a screen VM.
 *
 *   • INVERTED-PARALLEL-PARTIAL — rework counterpart at :presentation/.../settings/
 *     SettingsViewModel.kt owns the full toggle/clear/cache MVI surface. The 10 dropped legacy
 *     methods were already supplanted by rework use cases (SetAppTheme + SetPureBlack +
 *     UpdateSettingsToggle + ClearCache), which reach via :data strangler-figs. Legacy VM stays
 *     LIVE narrowly because App.kt's theme-bootstrap path was never re-routed.
 *
 *   • TASK-397-COMPONENTPRUNE-LINEAGE-PRESERVED — the 36-line class KDoc (lines 17-52) documents
 *     Task #397's removal of 10 orphan members + 3 coupled imports (MutableStateFlow,
 *     kotlinx.coroutines.launch, IODispatcher). The 3-name LIVE-members manifest at lines 44-47
 *     is the verified-by-grep preserved-set. PRESERVE — load-bearing componentprune audit per
 *     §253.
 *
 *   • KDOC-MIGRATION-NOTES-LOAD-BEARING — the 5-line Phase 9.7 prose (lines 11-15) covers Hilt-
 *     drop + AndroidViewModel→ViewModel narrowing. PRESERVE — no forward-work pointers.
 *
 *   • CROSS-PACKAGE-DEPENDENCY-LIVE — 6 imports: androidx.lifecycle (ViewModel + viewModelScope) +
 *     kotlinx.coroutines.flow (SharingStarted + StateFlow + stateIn) + 1 legacy SettingsRepository.
 *     All LIVE.
 *
 *   • THEME-BOOTSTRAP-CELL-OF-TRUTH-INVARIANT — the 3 StateFlows use `SharingStarted.Eagerly` +
 *     synchronous initialValue from `settingsRepo.isDarkMode()` / `isPureBlack()` / `isFollowSystem()`
 *     to AVOID a Compose first-frame flicker (un-themed → themed). DO NOT change to Lazily/
 *     WhileSubscribed during cleanup — would re-introduce the bootstrap-flicker regression that
 *     drove the Eagerly choice originally.
 */

