package me.manga.kira.domain.repository

import kotlinx.coroutines.flow.Flow
import me.manga.kira.domain.model.language.Language

/**
 * Reactive app-language access — observe the current selection, look up the supported list, and
 * persist the user's pick (including the platform locale switch where applicable).
 *
 * Phase 7.x.language rework. The `:data` impl strangler-fig delegates to the legacy `:shared`
 * `me.manga.kira.presentation.features.settings.domain.SettingsRepository.languageFlow` /
 * `setLanguage(code)` AND to the `:shared` `me.manga.kira.core.locale.applyApplicationLocale`
 * expect/actual (Android: AppCompat locale-list write; iOS/Desktop: no-op — see
 * `LocaleSwitcher.kt`). Same strangler-fig posture as
 * [me.manga.kira.domain.repository.ThemeRepository].
 *
 * Contract §6 SRP: owns ONE rule — "expose the app language as a read + set surface for the
 * language picker". Both the selected-code flow and the static supported-language list live here
 * because they're sub-aspects of one concern: which language the user has chosen, and which
 * languages they can choose from. Splitting into a sibling `SupportedLanguagesRepository` would
 * over-segment a coherent surface for zero benefit.
 *
 * Contract §6 ISP: three methods — one read flow + one read list + one mutator. The legacy
 * `SettingsRepository` facade exposes 13 methods (`incognitoFlow`, `clearFilesLargerThan1MB`,
 * `getCacheFolderSize`, `setReadingMode`, etc.); the rework interface declares only the slice it
 * needs. The other 12 stay on the legacy facade for their existing consumers.
 *
 * Contract §6 DIP: consumers (the three use cases —
 * [me.manga.kira.domain.usecase.language.ObserveSelectedLanguageUseCase] /
 * [me.manga.kira.domain.usecase.language.GetSupportedLanguagesUseCase] /
 * [me.manga.kira.domain.usecase.language.SetLanguageUseCase], and through them the rework
 * `LanguageViewModel`) depend on this interface, never on the legacy facade or DataStore directly.
 * Koin binds the impl at the composition root in `languageReworkModule`.
 *
 * Lifecycle expectation: the impl is bound as a `single` (matching the upstream legacy
 * `SettingsRepository`'s `single` lifecycle from `SharedModule`). A `factory` would resubscribe
 * the upstream DataStore flow on each resolution — wasteful for a setting read across the app's
 * lifetime.
 *
 * Behavior preservation: both the legacy `Screen.Language` route and the rework
 * `Screen.LanguageRework` route write to the same `DataStoreHelper.languageFlow`, so selecting a
 * language on either route propagates to the other. The legacy route also opens a `FeedbackDialog`
 * for the "Request New Language" entry — the rework foundation slice omits that entry; the
 * cross-cutting `ComplaintViewModel` integration is deferred to `Phase 7.x.language.request`.
 * Phase 9.x route-swap will retire the legacy route.
 *
 * **Audit-trail postscript** (Phase 9.x.cluster26.staleKdocSweep.cascade,
 * Task #482, 2026-05-28): one fulfilled-forecast citation appears
 * above:
 *  - Line 45 ("Phase 9.x route-swap will retire the legacy route").
 *    FACTUALLY INVERTED — Phase 7.x.language.swap (§292) re-pointed
 *    `Screen.Language`'s rendering adapter to the rework
 *    `LanguageScreen` (7.x-prefixed, earlier than the §253-era
 *    forecast predicted); Phase 9.x.language.retire (§350 sweep,
 *    "delete unreachable legacy Language screen + VM + LanguageOption")
 *    deleted the legacy `:composeApp/.../features/language/ui/screens/
 *    LanguageSelectionScreen.kt` + the legacy `LanguageViewModel` + the
 *    legacy `LANGUAGE_DISPLAY_NAMES` helper map. The "Request New
 *    Language" entry was subsequently restored on the rework path via
 *    Phase 7.x.language.request (§250 — already-completed task per
 *    the task list). HOWEVER — the legacy `:shared`
 *    `me.manga.kira.presentation.features.settings.domain.
 *    SettingsRepository.languageFlow` / `setLanguage(code)` +
 *    `DataStoreHelper.languageFlow` + `core.locale.applyApplicationLocale`
 *    STILL EXIST as the cell of truth that the rework `:data`
 *    `LanguageRepositoryImpl` delegates to via constructor injection
 *    (verified at `LanguageRepositoryImpl`'s `private val legacy:
 *    LegacySettingsRepository` + the cluster24 / §480 audit-trail
 *    postscript covering §§292 + 350 from the `:data` impl angle).
 *    The forecast resolved cleanly across both §292 (route-swap) +
 *    §350 (legacy-screen-retire) — both predicted phases executed,
 *    only the legacy `SettingsRepository` facade remains as the
 *    persistence backbone (cross-cutting cell shared with the rework
 *    Theme picker + Settings hub). The SRP / ISP / DIP / lifecycle /
 *    behavior-preservation sub-sections all stand on their own merits
 *    past the §§292 + 350 fulfilled landings. The LanguageRepository
 *    interface remains LIVE as the canonical rework language
 *    read+set surface. Original §253-era prose preserved verbatim
 *    per the audit-trail-preservation convention — the citation is
 *    historical record of the design lineage including the deferred-
 *    route-swap forecast that was subsequently fulfilled across
 *    §§292 + 350.
 */
interface LanguageRepository {

    /**
     * Reactive selected language code. Emits the latest IETF tag persisted in
     * `DataStoreHelper.languageFlow`. First-run emission is the empty string `""` (matching the
     * upstream non-nullable default — see `DataStoreHelper.languageFlow` in `:shared/core/`); the
     * picker renders no selected-row icon in that state.
     */
    fun observeSelectedLanguageCode(): Flow<String>

    /**
     * Synchronous lookup of the supported languages. The list is a compile-time constant in the
     * `:data` impl — 11 IETF tags with native endonyms, mirroring the upstream
     * `Res.array.supported_languages` + legacy `LANGUAGE_DISPLAY_NAMES` map.
     *
     * Sync (not `suspend`, not `Flow`) because the list does not change at runtime; making it
     * async would force every consumer through a `flow {}` builder for zero observable benefit.
     * If a future slice loads the list from settings / remote-config, the signature can become
     * `Flow<List<Language>>` — but that change would propagate to the VM's `init {}` collector,
     * not callers of this method.
     */
    fun getSupportedLanguages(): List<Language>

    /**
     * Persist the user's language selection AND apply the platform locale switch.
     *
     * Side-effect contract (preserved from the legacy `LanguageViewModel.selectLanguage(code)`):
     *  1. Writes [code] into `DataStoreHelper.languageFlow` via
     *     `SettingsRepository.setLanguage(code)`.
     *  2. Invokes `core.locale.applyApplicationLocale(code)`:
     *     - Android: `AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(code))`
     *       — recreates the activity tree under the new locale.
     *     - iOS / Desktop: no-op — switching locale mid-process would crash; the persisted
     *       preference takes effect on next launch.
     *
     * `suspend` is declared because the DataStore write is `suspend`. The
     * `applyApplicationLocale` call is sync on all platforms.
     */
    suspend fun setLanguage(code: String)
}
