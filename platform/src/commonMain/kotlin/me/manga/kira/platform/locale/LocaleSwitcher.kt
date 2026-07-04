package me.manga.kira.platform.locale

/**
 * Apply a per-app locale at runtime.
 *
 * Phase 5.6 relocates this SPI from `:shared/core/locale/LocaleSwitcher` (an `expect fun
 * applyApplicationLocale(...)` top-level function) into `:platform/locale/LocaleSwitcher`
 * (a contract `interface` with three per-target implementations). The legacy `:shared`
 * surface stays in place during the transition so the existing `LanguageViewModel` keeps
 * compiling. Phase 6+ rewires the consumer through Koin against the `:platform` interface.
 *
 * Originally ported from Android's
 * `AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(code))`. The
 * user-selected language tag is already persisted via `DataStoreHelper.setLanguage(...)`;
 * this call just notifies the framework to recreate the activity tree under the new locale.
 *
 * Platform behaviour:
 *  - **Android**: `AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(tag))`.
 *  - **iOS**: no-op — iOS apps switch language via `NSUserDefaults` key `AppleLanguages`
 *    and a hard process restart. Doing that mid-session would crash the running app. The
 *    persisted preference is the source of truth for the next launch.
 *  - **Desktop**: no-op — JVM has no per-app locale switching API; `Locale.getDefault()`
 *    is JVM-wide and Compose Multiplatform resolves locale at composition. The persisted
 *    preference takes effect on next app launch.
 *
 * Returning a result is intentionally absent — every call is fire-and-forget; the
 * persisted preference is what guarantees correctness on relaunch.
 *
 * **Audit-trail postscript** (Phase 9.x.cluster144.staleKdocSweep.cascade,
 * Task #600, 2026-05-28): classified as follows after recursive symbol
 * verification (one-hundred-and-fifty-seventh sibling of the cluster57-143
 * sweep — fifth and closing file of the wave-26 :platform tier opening
 * cluster144 5-leaf-bedrock-UX batch alongside ToastShower plus
 * IntentLauncher plus AppFileSystem plus FileSizeFormatter; closes
 * cluster144):
 *  (a) "Apply-a-per-app-locale-at-runtime + Phase-5.6-relocates-this-
 *  SPI-from-:shared-core-locale-LocaleSwitcher-an-expect-fun-
 *  applyApplicationLocale-top-level-function-into-:platform-locale-
 *  LocaleSwitcher-a-contract-interface-with-three-per-target-
 *  implementations + The-legacy-:shared-surface-stays-in-place-during-
 *  the-transition-so-the-existing-LanguageViewModel-keeps-compiling +
 *  Phase-6-plus-rewires-the-consumer-through-Koin-against-the-:platform-
 *  interface + Originally-ported-from-Android-AppCompatDelegate.set-
 *  ApplicationLocales-LocaleListCompat.forLanguageTags-code + The-
 *  user-selected-language-tag-is-already-persisted-via-DataStoreHelper.
 *  setLanguage-this-call-just-notifies-the-framework-to-recreate-the-
 *  activity-tree-under-the-new-locale" — LIVE-NOT-STALE plus PARTIALLY-
 *  FULFILLED-FORECAST. Verified: the 1-method SPI (applyApplication-
 *  Locale) wired with 3 actuals at platform/src/{android,ios,desktop}-
 *  Main/. The "Phase 6+ rewires LanguageViewModel through Koin" claim
 *  is PARTIALLY-FULFILLED — the rework LanguageViewModel consumes
 *  LocaleSwitcher via Koin (SetLanguageUseCase + LanguageRepository
 *  rework wiring), but the legacy :shared `core.locale.
 *  applyApplicationLocale` top-level function is still referenced at
 *  several strangler-fig sites (cross-classified at Task #422 BLOCKER
 *  on the §250 shadow-legacy-facade retire path).
 *  (b) "Platform-behaviour + Android-AppCompatDelegate.setApplication-
 *  Locales-LocaleListCompat.forLanguageTags-tag + iOS-no-op-iOS-apps-
 *  switch-language-via-NSUserDefaults-key-AppleLanguages-and-a-hard-
 *  process-restart-Doing-that-mid-session-would-crash-the-running-app +
 *  Desktop-no-op-JVM-has-no-per-app-locale-switching-API +
 *  Compose-Multiplatform-resolves-locale-at-composition + Returning-a-
 *  result-is-intentionally-absent-every-call-is-fire-and-forget-the-
 *  persisted-preference-is-what-guarantees-correctness-on-relaunch" —
 *  LIVE-NOT-STALE. Verified: AndroidLocaleSwitcher routes through
 *  AppCompatDelegate.setApplicationLocales as documented; iOS +
 *  Desktop actuals are intentionally no-op (the iOS no-op-because-
 *  process-restart-would-crash rationale remains current; Desktop has
 *  no per-app locale API). The fire-and-forget contract (no return
 *  value, persisted preference is source of truth on relaunch) is
 *  honored across all 3 actuals.
 *  Two classifications STAND on their own merits. Closes cluster144.
 *  Original Phase 5.6 (Task #169) :platform-relocation prose preserved
 *  verbatim per the audit-trail-preservation convention.
 */
interface LocaleSwitcher {

    /** Apply [languageTag] (BCP-47, e.g. `"en"`, `"ar-EG"`) as the active app locale. */
    fun applyApplicationLocale(languageTag: String)
}
