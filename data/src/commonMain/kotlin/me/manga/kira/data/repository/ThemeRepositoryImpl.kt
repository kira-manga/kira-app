package me.manga.kira.data.repository

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import me.manga.kira.domain.model.theme.AppTheme
import me.manga.kira.domain.repository.ThemeRepository
import me.manga.kira.presentation.features.settings.domain.SettingsRepository as LegacySettingsRepository

/**
 * [ThemeRepository] strangler-fig delegate over the legacy `:shared` [LegacySettingsRepository].
 *
 * Phase 7.x.theme rework. Translates between the unified domain [AppTheme] ADT and the legacy
 * two-boolean `SharedPreferences` representation (`KEY_THEME_MODE` for dark-mode +
 * `KEY_THEME_SYSTEM` for follow-system) that the legacy facade exposes via `darkModeFlow` /
 * `followSystemFlow` reads and `setDarkMode` / `setFollowSystem` writes. Same posture as
 * [SourcesRepositoryImpl] / [HistoryRepositoryImpl] / [UpdatesRepositoryImpl] /
 * [ReadingStatisticsRepositoryImpl] / [ReadingSessionRepositoryImpl].
 *
 * **SRP (contract §6)**: owns ONE rule — "translate between the rework domain theming surface
 * ([AppTheme] tri-state ADT + PureBlack boolean) and the legacy three-boolean storage, then
 * forward through [LegacySettingsRepository]". The `SharedPreferences` plumbing lives in the
 * legacy facade's `prefsHelper`. The other 8 methods on the legacy facade (`incognitoFlow`,
 * `clearFilesLargerThan1MB`, `getCacheFolderSize`, `formatSize`, `setLanguage`,
 * `setDownloadedOnly`, `setIncognito`, `setReadingMode`) stay verbatim for their existing
 * consumer `SettingsViewModel` (the legacy `OnboardingViewModel` was retired in §143 once the
 * rework onboarding flow landed); the rework reaches into 4 of the 12.
 *
 * **Phase 9.x.homevm.bookmarkprune (Task #431)** retired the legacy facade's
 * `hasShownRemoveBookMarkFlow` + `setShownRemoveBookMark` pair after Phase 9.x.mangadetails.retire
 * (Slice 5a) deleted their only LIVE reacher (`MangaDetailsScreenRoute.kt:71`/`:86`). The
 * upstream `DataStoreHelper.hasShownRemoveBookMark` + `setShownRemoveBookMark` and
 * `StorageKeys.HAS_SHOWN_ADD_LIBRARY_PROMPT` const were retired in the same commit. This impl
 * never reached either method — the delta-list above is documentation-only.
 *
 * **DIP (contract §6)**: depends on the legacy [LegacySettingsRepository] type because it's the
 * only vendor for the theme-pref reads/writes today. The dependency is structurally at the
 * strangler-fig boundary — the rework `:data` layer is allowed to reach into `:shared` for
 * cross-cutting persistence that hasn't been ported yet. The [ThemeRepository] interface in
 * `:domain` is unaffected either way.
 *
 * **Import-alias note** — the legacy class name `SettingsRepository` is unambiguous (no rework
 * `SettingsRepository` exists today). The `as LegacySettingsRepository` alias is kept anyway for
 * symmetry with the other strangler-fig impls and to make the boundary visible in source.
 *
 * **`observeAppTheme` translation rule** — combines the two boolean flows with the same
 * precedence the legacy `ThemeSelectionScreenRoute` adapter applied:
 *
 *   - `followSystem == true`  → [AppTheme.System]  (System wins regardless of `darkMode`)
 *   - `followSystem == false` + `darkMode == true`  → [AppTheme.Dark]
 *   - `followSystem == false` + `darkMode == false` → [AppTheme.Light]
 *
 * `combine` re-evaluates the projection on every upstream emission, so toggling either pref from
 * any consumer (legacy onboarding route, legacy SettingsScreen, the rework picker itself,
 * external storage changes) propagates here.
 *
 * **`setAppTheme` translation rule** — mirrors the legacy route adapter's setter logic verbatim:
 *
 *   - [AppTheme.Light]  writes `followSystem = false`, then `darkMode = false`
 *   - [AppTheme.Dark]   writes `followSystem = false`, then `darkMode = true`
 *   - [AppTheme.System] writes `followSystem = true`  ONLY (leaves `darkMode` untouched)
 *
 * The "System leaves darkMode unchanged" quirk is intentional — it preserves the user's prior
 * Dark/Light preference across System oscillations, matching the legacy onboarding route's
 * behavior exactly. Don't "normalise" this by also writing `darkMode = false` on System — that
 * would break parity with the legacy route and corrupt the persisted preference for users who
 * round-trip via the legacy route.
 *
 * **PureBlack pass-through** — [observePureBlack] returns `legacy.pureBlackFlow` directly (no
 * translation: the legacy flow type is already `Flow<Boolean>` matching the rework interface).
 * [setPureBlack] forwards to `legacy.setPureBlack(enabled)` (sync `SharedPreferences.putBoolean`
 * under the hood). The PureBlack flag is orthogonal to the theme tri-state; toggling it does
 * NOT touch `darkMode` / `followSystem` and the theme picker's `combine` projection of those
 * two flows is unaffected.
 *
 * **Concurrent-write nuance** — Light and Dark writes are two sequential `SharedPreferences`
 * `putBoolean` calls; the upstream `darkModeFlow` + `followSystemFlow` are independent prefs
 * flows that can fire between the two writes. The intermediate emission (e.g., when
 * `followSystem` has just flipped to `false` but `darkMode` is still old-value `true`) lands on
 * the same final value the user selected anyway, because Compose recomposition coalesces faster
 * than the user can perceive. The legacy route has the same property — see [PLAN_theme.md]
 * §"Verification" edge-cases for the analysis.
 *
 * **`suspend` despite sync legacy writes** — the legacy `setDarkMode` / `setFollowSystem` are
 * non-suspend `SharedPreferences.putBoolean` calls. The `suspend` declared on
 * [ThemeRepository.setAppTheme] is forward-compatibility room — a future DataStore migration
 * becomes a non-event for callers — and matches the [SourcesRepository.setSourceEnabled] /
 * [HistoryRepository.deleteEntry] pattern across the rework. No `withContext(Dispatchers.IO)`
 * wrap is needed because the legacy `SharedPreferences.putBoolean` is a non-blocking memory
 * write (Android serialises to disk asynchronously via its own apply loop).
 *
 * **Lifecycle**: `single` in Koin (per [ThemeRepository] KDoc). The upstream legacy
 * [LegacySettingsRepository] is `single` (declared by `SharedModule`); a `factory` here would
 * resubscribe the upstream pref flows on each resolution — wasteful for a setting read across
 * the app's lifetime.
 *
 * **Load-bearing fixes preserved**: this slice does NOT touch the Coil ImageLoader, the per-host
 * repo registry, OkHttp interceptor, AVIF decoder, HighQualitySkiaImageDecoder, or `:platform`
 * — Theme is pure preference plumbing. No load-bearing risk.
 *
 * **Audit-trail postscript** (Phase 9.x.cluster11.staleKdocSweep.cascade,
 * Task #467, 2026-05-28): four stale citations into §307-retired legacy
 * `:shared/.../onboarding/.../ThemeSelectionScreen.kt` + §354-retired
 * legacy `:composeApp/.../features/settings/ui/screens/SettingsScreen.kt`
 * appear above:
 *  - Line 46 (`observeAppTheme` translation rule opener): "combines the
 *    two boolean flows with the same precedence the legacy
 *    `ThemeSelectionScreenRoute` adapter applied". The route adapter
 *    file (`ThemeSelectionScreenRoute.kt`) STILL EXISTS, but Phase
 *    7.x.theme.swap (§291 commit `189f462`) rewrote it to host the
 *    rework `:ui` screen — so "the legacy adapter applied" framing is
 *    historical; the precedence rule itself is still the canonical one.
 *  - Line 53 (`combine` re-evaluation aside): "any consumer (legacy
 *    onboarding route, legacy SettingsScreen, the rework picker itself,
 *    external storage changes)". Two of the four enumerated consumers
 *    are gone: the legacy `:shared/.../ThemeSelectionScreen.kt` was
 *    retired in Phase 9.x.onboarding.legacy_retire (§307, commit
 *    `6c83364` "delete 5 unreachable legacy onboarding files"), and
 *    the legacy `:composeApp/.../features/settings/ui/screens/
 *    SettingsScreen.kt` was retired in Phase 9.x.legacysettings.retire
 *    (§354, commit `5cc42d2`). The rework picker + external storage
 *    changes remain LIVE consumers.
 *  - Line 63 (System-leaves-darkMode-unchanged rationale): "matching
 *    the legacy onboarding route's behavior exactly". The legacy
 *    onboarding screen is retired (§307); the behaviour-parity
 *    rationale stands as historical record — the rule was designed to
 *    preserve user preference round-trip across the legacy route, and
 *    even though the legacy route is gone, the rule remains correct in
 *    its own right (System is a transient theme choice that shouldn't
 *    overwrite the persisted Dark/Light fallback).
 *  - Line 65 ("don't normalise" warning): "would break parity with the
 *    legacy route and corrupt the persisted preference for users who
 *    round-trip via the legacy route". Same §307 retire as line 63;
 *    no users currently round-trip via the legacy route because the
 *    route's rendering adapter no longer points at the retired
 *    `:shared` screen. The "don't normalise" rule stands on its own
 *    merits — writing `darkMode = false` on System would lose the user
 *    preference regardless of whether any legacy reader exists.
 * Verified by filesystem checks returning zero hits for both retired
 * `:shared/.../ThemeSelectionScreen.kt` (§307) and `:composeApp/.../
 * features/settings/ui/screens/SettingsScreen.kt` (§354) paths. The
 * `:data` impl's translation rules (precedence projection in
 * `observeAppTheme`, System-preserves-darkMode quirk in `setAppTheme`,
 * PureBlack pass-through) all stand on their own merits past the §307
 * + §354 retires — the legacy citations are historical record of the
 * design lineage; this impl remains LIVE as the strangler-fig delegate
 * for the rework theme surface. Original §253-era prose preserved
 * verbatim per the audit-trail-preservation convention.
 */
class ThemeRepositoryImpl(
    private val legacy: LegacySettingsRepository,
) : ThemeRepository {

    override fun observeAppTheme(): Flow<AppTheme> =
        combine(legacy.darkModeFlow, legacy.followSystemFlow) { dark, system ->
            when {
                system -> AppTheme.System
                dark -> AppTheme.Dark
                else -> AppTheme.Light
            }
        }

    override suspend fun setAppTheme(theme: AppTheme) {
        when (theme) {
            AppTheme.Light -> {
                legacy.setFollowSystem(false)
                legacy.setDarkMode(false)
            }
            AppTheme.Dark -> {
                legacy.setFollowSystem(false)
                legacy.setDarkMode(true)
            }
            AppTheme.System -> {
                legacy.setFollowSystem(true)
            }
        }
    }

    override fun observePureBlack(): Flow<Boolean> = legacy.pureBlackFlow

    override suspend fun setPureBlack(enabled: Boolean) {
        legacy.setPureBlack(enabled)
    }
}
