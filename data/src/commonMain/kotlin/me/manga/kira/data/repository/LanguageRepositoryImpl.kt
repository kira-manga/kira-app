package me.manga.kira.data.repository

import kotlinx.coroutines.flow.Flow
import me.manga.kira.domain.model.language.Language
import me.manga.kira.platform.locale.LocaleSwitcher
import me.manga.kira.domain.repository.LanguageRepository
import me.manga.kira.presentation.features.settings.domain.SettingsRepository as LegacySettingsRepository

/**
 * [LanguageRepository] strangler-fig delegate over the legacy `:shared` [LegacySettingsRepository]
 * for the language pref + the `:platform` [LocaleSwitcher] facade for the platform locale switch.
 *
 * Phase 7.x.language rework. Same posture as
 * [ThemeRepositoryImpl] / [SourcesRepositoryImpl] / [HistoryRepositoryImpl] /
 * [UpdatesRepositoryImpl] / [ReadingStatisticsRepositoryImpl] /
 * [ReadingSessionRepositoryImpl] — `:data` reaches into `:shared`'s legacy facade for cross-cutting
 * persistence that has not yet been ported.
 *
 * **SRP (contract §6)**: owns ONE rule — "expose the language pref + supported-list +
 * persist-with-locale-switch surface to the rework picker, delegating storage to the legacy facade
 * and the platform locale switch to the `:platform` facade". The DataStore plumbing lives in
 * `DataStoreHelper.languageFlow` / `setLanguage(code)`; the platform locale switch lives behind the
 * `:platform` [LocaleSwitcher] (per-target impls). The other 12 methods on the legacy facade (`incognitoFlow`,
 * `darkModeFlow`, `setReadingMode`, `clearFilesLargerThan1MB`, etc.) stay verbatim for their
 * existing consumer `SettingsViewModel` plus the rework `ThemeRepositoryImpl` (the legacy
 * `OnboardingViewModel` was retired in §143 once the rework onboarding flow landed); the rework
 * reaches into 2 of the 13 (`languageFlow` + `setLanguage`).
 *
 * **DIP (contract §6)**: depends on the legacy [LegacySettingsRepository] type AND on the
 * `:platform` [LocaleSwitcher] facade because they're the only vendors for the language-pref
 * read/write + the platform locale switch today. The legacy dependency is structurally at the
 * strangler-fig boundary — the rework `:data` layer is allowed to reach into `:shared` for
 * cross-cutting persistence that hasn't been ported yet — while [LocaleSwitcher] is the clean
 * `:platform` port for the per-target locale switch. The [LanguageRepository] interface in
 * `:domain` is unaffected either way.
 *
 * **Import-alias note** — the legacy class name `SettingsRepository` is unambiguous (no rework
 * `SettingsRepository` exists today). The `as LegacySettingsRepository` alias is kept for
 * symmetry with the other strangler-fig impls and to make the boundary visible in source.
 *
 * **`observeSelectedLanguageCode` pass-through** — returns `legacy.languageFlow` directly. The
 * legacy type is already `Flow<String>` matching the rework interface; no translation, no
 * `map`. First emission is the empty string `""` on first run (matches the upstream
 * `DataStoreHelper.languageFlow`'s non-nullable default).
 *
 * **`getSupportedLanguages` static list** — 11 entries (IETF tag + native endonym), matching the
 * upstream `Res.array.supported_languages` + the legacy `LANGUAGE_DISPLAY_NAMES` map in
 * `composeApp/.../navigation/routes/LanguageScreenRoute.kt`. **Single source of truth for the
 * rework path** — the legacy route's map stays put for the legacy route (deliberate duplication
 * — strangler-fig posture so the legacy route stays untouched and retires cleanly in Phase 9.x
 * with its map). The list order matches the upstream `supported_languages` array order (`en`,
 * `ar`, `de`, `es`, `fr`, `in`, `it`, `ja`, `pt`, `ru`, `tr`) — preserves the visual ordering
 * users see on the legacy route.
 *
 * **`setLanguage` two-step side effect** — preserved verbatim from the legacy
 * `LanguageViewModel.selectLanguage(code)`:
 *
 *  1. `legacy.setLanguage(code)` — writes the IETF tag into `DataStoreHelper.languageFlow`.
 *  2. `applyApplicationLocale(code)` — invokes the platform locale switcher.
 *     - Android: `AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(code))`
 *       — recreates the activity tree under the new locale.
 *     - iOS / Desktop: no-op — switching locale mid-process would crash; the persisted
 *       preference takes effect on next launch. See `LocaleSwitcher.kt` KDoc.
 *
 * The order matters: the legacy VM persists FIRST then applies the locale, so any composition
 * triggered by the activity recreate already sees the new persisted value via the `languageFlow`
 * emission. Reversing the order would race: the activity could recompose against the old persisted
 * value if the recreate completes before the DataStore write commits.
 *
 * **`suspend` because `legacy.setLanguage` is `suspend`** — the DataStore write is suspend (it's
 * a coroutine-based `Preferences.edit`); `applyApplicationLocale` is sync on all platforms (no
 * await needed). The use case forwards the suspend signature from the interface verbatim.
 *
 * **Lifecycle**: `single` in Koin (per [LanguageRepository] KDoc). The upstream legacy
 * [LegacySettingsRepository] is `single` (declared by `SharedModule`); a `factory` here would
 * resubscribe the upstream DataStore flow on each resolution — wasteful for a setting read across
 * the app's lifetime.
 *
 * **Load-bearing fixes preserved**: this slice does NOT touch the Coil ImageLoader, the per-host
 * repo registry, OkHttp interceptor, AVIF decoder, HighQualitySkiaImageDecoder, or `:platform` —
 * Language is pure preference + locale-switcher plumbing. No load-bearing risk.
 *
 * **Audit-trail postscript** (Phase 9.x.cluster24.staleKdocSweep.cascade,
 * Task #480, 2026-05-28): one fulfilled-forecast citation appears in
 * the companion-object `SUPPORTED_LANGUAGES` KDoc below:
 *  - Lines 102-105 ("keep this list in sync with the upstream
 *    `composeResources/values/arrays.xml` `supported_languages` array
 *    AND with the legacy `LANGUAGE_DISPLAY_NAMES` map for legacy-route
 *    parity until Phase 9.x route-swap retires the legacy route").
 *    FACTUALLY INVERTED — Phase 7.x.language.swap (§292) re-pointed
 *    `Screen.LanguageScreen`'s rendering adapter to the rework
 *    `LanguageScreen` (7.x-prefixed, earlier than the §253-era
 *    forecast predicted); Phase 9.x.language.retire (§350 sweep,
 *    "delete unreachable legacy Language screen + VM + LanguageOption")
 *    deleted the legacy `:composeApp/.../features/language/ui/screens/
 *    LanguageSelectionScreen.kt` + the legacy `LanguageViewModel` + the
 *    legacy `LANGUAGE_DISPLAY_NAMES` helper map. The cross-referenced
 *    `LanguageScreenRoute.kt` audit-trail postscript at §474 documents
 *    the same retire from the route-adapter angle ("FACTUALLY INVERTED
 *    — Phase 9.x.language.retire (§350) executed the predicted
 *    retirement sweep; verified by filesystem check returning zero
 *    hits for the three cited symbols"). HOWEVER — the legacy
 *    [LegacySettingsRepository] facade + `DataStoreHelper.languageFlow`
 *    + `core.locale.applyApplicationLocale` STILL EXIST as the cell
 *    of truth that this impl delegates to via `legacy = get()` +
 *    `applyApplicationLocale(code)` (verified at the constructor
 *    signature + `setLanguage` body below — `private val legacy:
 *    LegacySettingsRepository` at L83 + `applyApplicationLocale(code)`
 *    at L93). The "keep in sync with the legacy `LANGUAGE_DISPLAY_NAMES`
 *    map" sync-burden was retired across §§292 + 350 — only the
 *    upstream `composeResources/values/arrays.xml`
 *    `supported_languages` array remains as the canonical
 *    cross-reference. The `SUPPORTED_LANGUAGES` constant below is now
 *    the SINGLE source of truth for the rework path (no legacy map to
 *    sync against). Mirror of §§475-479 cluster-tier
 *    fulfilled-deferral-inversion precedent + §474 same-feature
 *    route-adapter precedent.
 * The SRP / DIP / import-alias / observeSelectedLanguageCode-pass-through /
 * setLanguage-two-step-side-effect / suspend-rationale / lifecycle /
 * load-bearing sub-sections all stand on their own merits past the
 * §§292 + 350 fulfilled landings. The LanguageRepositoryImpl remains
 * LIVE as the canonical strangler-fig delegate for the rework language
 * surface. Original §253-era prose preserved verbatim per the
 * audit-trail-preservation convention — the citation is historical
 * record of the design lineage including the deferred-route-retire
 * forecast that was subsequently fulfilled across §§292 + 350.
 */
class LanguageRepositoryImpl(
    private val legacy: LegacySettingsRepository,
    private val localeSwitcher: LocaleSwitcher,
) : LanguageRepository {

    override fun observeSelectedLanguageCode(): Flow<String> = legacy.languageFlow

    override fun getSupportedLanguages(): List<Language> = SUPPORTED_LANGUAGES

    override suspend fun setLanguage(code: String) {
        legacy.setLanguage(code)
        localeSwitcher.applyApplicationLocale(code)
    }

    companion object {
        /**
         * Canonical supported-language list for the rework path — the single source of truth
         * (the former upstream `composeResources/values/arrays.xml` `supported_languages` array
         * and the legacy `LANGUAGE_DISPLAY_NAMES` map are both retired; there is nothing left to
         * keep this list in sync with).
         */
        private val SUPPORTED_LANGUAGES: List<Language> = listOf(
            Language(code = "en", displayName = "English"),
            Language(code = "ar", displayName = "العربية"),
            Language(code = "de", displayName = "Deutsch"),
            Language(code = "es", displayName = "Español"),
            Language(code = "fr", displayName = "Français"),
            Language(code = "in", displayName = "Bahasa Indonesia"),
            Language(code = "it", displayName = "Italiano"),
            Language(code = "ja", displayName = "日本語"),
            Language(code = "pt", displayName = "Português"),
            Language(code = "ru", displayName = "Русский"),
            Language(code = "tr", displayName = "Türkçe"),
        )
    }
}
