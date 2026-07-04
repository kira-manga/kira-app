package me.manga.kira.domain.model.theme

/**
 * User-selectable application theme.
 *
 * Phase 7.x.theme rework. The canonical theme ADT — collapses the legacy `:shared`
 * `SettingsRepository`'s two-boolean representation (`darkMode` + `followSystem`) into a single
 * tri-state value. The `:data` layer's [me.manga.kira.data.repository.ThemeRepositoryImpl]
 * translates between this ADT and the legacy boolean storage; the legacy onboarding-local enum
 * (`composeApp/.../onboarding/theme_selection/ThemeSelectionScreen.kt:166`) is preserved for the
 * legacy route and will be retired by the Phase 9.x route-swap.
 *
 * Why a domain-layer enum rather than a `:ui` / `:presentation` one: same posture as
 * [me.manga.kira.domain.model.reader.ReadingMode] — the theme value is the canonical user
 * preference, exposed via the reactive [me.manga.kira.domain.repository.ThemeRepository], and
 * read by every layer (`:presentation` selects on it, `:ui` composables look up the localized
 * label). Putting it in `:domain` keeps every layer's dependency pointed inward.
 *
 * Resource concerns (Material icon + localized display label) are intentionally absent from the
 * enum constructor — both are `:ui` / `:composeApp` concerns and were the reason the legacy enum
 * pulled in compose-resources `StringResource` handles. The rework `:ui` theme picker resolves
 * icon + label via a `when (theme) { ... }` lookup co-located with the picker composable. Keeps
 * `:domain` Compose-free per the layer boundary contract.
 *
 * Contract §6 SRP: one rule — "the three theme variants as values". No methods, no derivation,
 * no Compose dependencies.
 *
 * Contract §17: no `!!`, no `Any`, no `lateinit` — enum constants only.
 *
 * **Audit-trail postscript** (Phase 9.x.cluster27.staleKdocSweep.cascade,
 * Task #483, 2026-05-28): one fulfilled-forecast citation appears
 * above:
 *  - Lines 9-11 ("the legacy onboarding-local enum
 *    (`composeApp/.../onboarding/theme_selection/ThemeSelectionScreen.kt
 *    :166`) is preserved for the legacy route and will be retired by
 *    the Phase 9.x route-swap"). FACTUALLY INVERTED — Phase 7.x.theme.
 *    swap (§291, Task #291) re-pointed `Screen.Theme`'s rendering
 *    adapter to the rework `:ui` ThemeScreen backed by the rework
 *    `ThemeViewModel` (7.x-prefixed, earlier than the §253-era forecast
 *    predicted); Phase 9.x.onboarding.legacy_retire (§307) DELETED the
 *    cited `composeApp/.../onboarding/theme_selection/
 *    ThemeSelectionScreen.kt` file in its entirety, taking the L166
 *    onboarding-local enum with it (Glob `**\/onboarding/theme_
 *    selection/ThemeSelectionScreen.kt` returns NO MATCHES — the
 *    cite-target no longer exists on disk; this is a STALE-SYMBOL-
 *    REFERENCE in addition to the route-swap forecast inversion).
 *    Phase 9.y.onboardingvm_retire (§308) deleted the legacy
 *    `OnboardingViewModel` that the pre-swap onboarding theme picker
 *    referenced. HOWEVER — the legacy `:shared`
 *    `me.manga.kira.presentation.features.settings.domain.
 *    SettingsRepository` facade + the underlying `SharedPreferences`
 *    booleans (`KEY_THEME_MODE` + `KEY_THEME_SYSTEM` + `KEY_PURE_BLACK`)
 *    STILL EXIST as the cell of truth that the rework `:data`
 *    `ThemeRepositoryImpl` delegates to via constructor injection
 *    (verified at `ThemeRepositoryImpl`'s `private val legacy:
 *    LegacySettingsRepository` + the cluster11 / §467 + cluster26 /
 *    §482 audit-trail postscripts covering §291 + §307 + §308 from the
 *    `:data` impl + sibling [ThemeRepository] interface angles). The
 *    forecast resolved cleanly across §§291 + 307 — both predicted
 *    phases executed, only the legacy `SettingsRepository` facade
 *    remains as the persistence backbone (cross-cutting cell shared
 *    with the rework Settings hub + Language picker). The SRP /
 *    domain-layer-rationale / Compose-free / Contract §17 sub-sections
 *    all stand on their own merits past the §§291 + 307 fulfilled
 *    landings. The [AppTheme] enum remains LIVE as the canonical
 *    rework theme-value ADT consumed by [ThemeRepository] +
 *    `ObserveAppThemeUseCase` + `SetAppThemeUseCase` + the rework
 *    `ThemeViewModel`. Original §253-era prose preserved verbatim per
 *    the audit-trail-preservation convention — the citation is
 *    historical record of the design lineage including the deferred-
 *    route-swap forecast that was subsequently fulfilled across §§291
 *    + 307 + 308.
 */
enum class AppTheme {
    Light,
    Dark,
    System,
}
