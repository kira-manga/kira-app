package me.manga.kira.domain.repository

import kotlinx.coroutines.flow.Flow
import me.manga.kira.domain.model.theme.AppTheme

/**
 * Reactive application-theme access — observe the current theme + set the user's selection.
 *
 * Phase 7.x.theme rework. The `:data` impl strangler-fig delegates to the legacy `:shared`
 * `me.manga.kira.presentation.features.settings.domain.SettingsRepository`, which stores theme
 * as two independent `SharedPreferences` booleans (`KEY_THEME_MODE` + `KEY_THEME_SYSTEM`). The
 * translation between this unified [AppTheme] ADT and the two-boolean storage lives in the impl —
 * keeping the domain layer free of the storage-shape artifact, and matching the legacy route
 * adapter's translation rules verbatim (see [PLAN_theme.md] §"Approach").
 *
 * Contract §6 SRP: owns ONE rule — "expose the app's theming surface (selection + variant) as a
 * read + set surface for the theme picker". Both the tri-state [AppTheme] selection and the
 * orthogonal PureBlack/OLED variant flag live here because they're sub-aspects of one concern:
 * how the app is themed. They share a single consumer (the rework `ThemeViewModel`); splitting
 * into a sibling `PureBlackRepository` would over-segment a coherent surface for zero benefit.
 *
 * Contract §6 ISP: four methods — two read flows + two mutators. The legacy facade exposes
 * 13 methods (`incognitoFlow`, `clearFilesLargerThan1MB`, `getCacheFolderSize`, `formatSize`,
 * `setLanguage`, etc.); the rework interface declares only the 4 the theme picker uses. The
 * other 9 stay on the legacy facade for their existing consumer `SettingsViewModel` (the
 * legacy `OnboardingViewModel` was retired in §143 once the rework onboarding flow landed).
 *
 * Contract §6 DIP: consumers (the 4 use cases — `ObserveAppThemeUseCase`, `SetAppThemeUseCase`,
 * `ObservePureBlackUseCase`, `SetPureBlackUseCase`, and through them the rework `ThemeViewModel`)
 * depend on this interface, never on the legacy facade or `SharedPreferences` directly. Koin
 * binds the impl at the composition root in `themeReworkModule`.
 *
 * Lifecycle expectation: the impl is bound as a `single` (matching the upstream legacy
 * `SettingsRepository`'s `single` lifecycle from `SharedModule`). A `factory` would resubscribe
 * the upstream pref flows on each resolution — wasteful for a setting read across the app's
 * lifetime.
 *
 * Behavior preservation: both the legacy onboarding route and the rework route write to the
 * same `SharedPreferences` keys, so toggling theme on either route propagates to the other.
 * Phase 9.x route-swap will retire the legacy route.
 *
 * **Audit-trail postscript** (Phase 9.x.cluster26.staleKdocSweep.cascade,
 * Task #482, 2026-05-28): one fulfilled-forecast citation appears
 * above:
 *  - Line 40 ("Phase 9.x route-swap will retire the legacy route").
 *    FACTUALLY INVERTED — Phase 7.x.theme.swap (§291, Task #291)
 *    re-pointed `Screen.Theme`'s rendering adapter to the rework
 *    `ThemeScreen` (7.x-prefixed, earlier than the §253-era forecast
 *    predicted); the legacy `ThemeSelectionScreenRoute.kt` was
 *    REWRITTEN to host the rework `:ui` ThemeScreen backed by the
 *    rework `ThemeViewModel` (Koin-bound via `themeReworkModule`).
 *    Phase 9.y.onboardingvm_retire (§308) deleted the legacy
 *    `OnboardingViewModel` that the pre-swap route adapter referenced.
 *    HOWEVER — the legacy `:shared`
 *    `me.manga.kira.presentation.features.settings.domain.
 *    SettingsRepository` facade + the underlying `SharedPreferences`
 *    booleans (`KEY_THEME_MODE` + `KEY_THEME_SYSTEM` + `KEY_PURE_BLACK`)
 *    STILL EXIST as the cell of truth that the rework `:data`
 *    `ThemeRepositoryImpl` delegates to via constructor injection
 *    (verified at `ThemeRepositoryImpl`'s `private val legacy:
 *    LegacySettingsRepository` + the cluster11 / §467 audit-trail
 *    postscript covering §§307 + 354 from the `:data` impl angle).
 *    The forecast resolved across §291 (route-swap) — the legacy
 *    `Screen.Theme` binding remains the canonical user-reachable
 *    route key (callers untouched) but the adapter targets the rework
 *    surface. Only the consumer-side onboarding VM was retired; the
 *    legacy `SettingsRepository` facade remains the rework's pref
 *    backbone (cross-cutting cell shared with the rework Settings
 *    hub's theme triplet — see [SettingsRepository] cluster26
 *    postscript below). The SRP / OCP / ISP / DIP / lifecycle /
 *    behavior-preservation sub-sections all stand on their own merits
 *    past the §291 fulfilled landing. The ThemeRepository interface
 *    remains LIVE as the canonical rework theme read+set surface.
 *    Original §253-era prose preserved verbatim per the audit-trail-
 *    preservation convention — the citation is historical record of
 *    the design lineage including the deferred-route-swap forecast
 *    that was subsequently fulfilled at §291.
 */
interface ThemeRepository {

    /**
     * Reactive current theme. Emits the latest value as a [AppTheme] tri-state.
     *
     * The impl combines the legacy `darkModeFlow` + `followSystemFlow` into a single emission
     * using the same precedence the legacy route adapter applied: `followSystem` wins (→ System),
     * otherwise `darkMode` decides (→ Dark / Light). See [PLAN_theme.md] for the truth table.
     *
     * First-run defaults: `darkMode` defaults `false`, `followSystem` defaults `true` →
     * first emission is [AppTheme.System].
     */
    fun observeAppTheme(): Flow<AppTheme>

    /**
     * Set the user's theme selection. Fire-and-forget — the upstream [observeAppTheme] flow
     * re-emits the new theme once the underlying writes commit.
     *
     * `suspend` is declared for forward-compatibility: the legacy storage is sync
     * `SharedPreferences.putBoolean`, but a future DataStore migration would make these writes
     * naturally async without requiring a caller-side change.
     *
     * Translation quirk (preserved verbatim from the legacy route adapter): setting
     * [AppTheme.System] writes `followSystem=true` but leaves `darkMode` unchanged, so a user
     * who oscillates Dark → System → Dark sees their prior Dark preference restored. Light and
     * Dark both write both booleans explicitly.
     */
    suspend fun setAppTheme(theme: AppTheme)

    /**
     * Reactive PureBlack/OLED toggle. Emits `true` when the dark color scheme should use true
     * black surfaces (AMOLED-friendly), `false` for the standard Material 3 dark surfaces.
     *
     * Phase 7.x.theme.pureblack. Orthogonal to [observeAppTheme] — the flag is stored
     * independently in the legacy `SharedPreferences` (`KEY_PURE_BLACK`). When the active theme
     * is [AppTheme.Light], the toggle is persisted but has no visual effect; when the active
     * theme is [AppTheme.Dark] or [AppTheme.System] (resolving dark), the toggle drives the
     * `darkColorScheme` variant.
     *
     * First-run default: `true`. Matches the legacy `SettingsRepository.isPureBlack()` default —
     * existing users see no behaviour change after the rework picker ships.
     */
    fun observePureBlack(): Flow<Boolean>

    /**
     * Set the PureBlack/OLED variant flag. Fire-and-forget — the upstream [observePureBlack]
     * flow re-emits the new value once the underlying write commits.
     *
     * `suspend` declared for forward-compatibility (mirrors [setAppTheme]'s rationale). The
     * legacy storage is sync `SharedPreferences.putBoolean`, but a future DataStore migration
     * becomes a non-event for callers.
     */
    suspend fun setPureBlack(enabled: Boolean)
}
