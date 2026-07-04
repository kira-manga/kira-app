package me.manga.kira.locale

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ProvidedValue
import androidx.compose.runtime.staticCompositionLocalOf
import platform.Foundation.NSLocale
import platform.Foundation.NSUserDefaults
import platform.Foundation.currentLocale
import platform.Foundation.languageCode

/**
 * iOS [LocalAppLocale] — writes the chosen language to `NSUserDefaults["AppleLanguages"]` (the key
 * iOS resolves preferred-language order from) and exposes the code through a composition local. The
 * app root keys its content on the language code so the keyed recomposition re-reads resources.
 * Blank/null restores the captured system default.
 *
 * **Live switch (PI2, 2026-07): the write IS visible within the running process.** String
 * resolution goes compose-resources → `androidx.compose.ui.text.intl.Locale.current` → darwin
 * `platformLocaleDelegate` → `NSLocale.preferredLanguages`, re-read on EVERY access with no
 * compose-side cache — and `preferredLanguages` reflects a same-process `AppleLanguages` write
 * (pinned empirically by `AppleLanguagesLiveSwitchContractTest`). The app root's `key(language)`
 * recomposition therefore re-resolves every `stringResource` in the new language immediately,
 * exactly like Android/Desktop. Known residual gap: SWIFT-side text (the native reader's
 * `ReaderStrings`, notification strings) resolves through `NSBundle`, which caches its
 * localization at launch — those few strings still catch up on the next launch.
 *
 * Two correctness details vs. the Android/Desktop actuals:
 *  - **True system default (A17).** [provides] overwrites `AppleLanguages`, so reading that key
 *    back on a later launch would return the *prior in-app choice*, not the device's original
 *    order. We therefore snapshot the original order **once ever** into a dedicated
 *    `KiraOriginalAppleLanguages` key (before any override can run) and always restore from it —
 *    so "restore system default" stays correct across sessions.
 *    **Upgrade-path limitation:** the snapshot is only the device's true original order for
 *    installs whose first run is on a build that has this key. A user who already changed the
 *    in-app language on an earlier build (where `AppleLanguages` already held their choice and the
 *    dedicated key did not yet exist) will have that prior choice enshrined as the "default" on
 *    first run of this build — their true device default is unrecoverable from `AppleLanguages`
 *    alone. This is acceptable today because no reachable UI restores the system default (the
 *    language picker lists concrete languages only); a future "system default" picker should seed
 *    this key during onboarding, before any override can be written.
 *  - **Seeded [current] (A18).** The composition local is seeded with the real system language tag
 *    (not `""`), so [current] reports the active language even before any override — matching
 *    Android/Desktop, which return `Locale.getDefault().toLanguageTag()`.
 */
actual object LocalAppLocale {
    private const val APPLE_LANGUAGES_KEY = "AppleLanguages"
    private const val ORIGINAL_LANGUAGES_KEY = "KiraOriginalAppleLanguages"

    /**
     * The device's original `AppleLanguages` order, preserved write-once. Captured at first object
     * access (which precedes the first [provides] override within that same call), then read back
     * from the dedicated key on every later launch so a persisted in-app choice can't masquerade
     * as the system default.
     */
    private val systemDefault: List<*>? = run {
        val defaults = NSUserDefaults.standardUserDefaults
        if (defaults.arrayForKey(ORIGINAL_LANGUAGES_KEY) == null) {
            defaults.arrayForKey(APPLE_LANGUAGES_KEY)?.let {
                defaults.setObject(it, ORIGINAL_LANGUAGES_KEY)
            }
        }
        defaults.arrayForKey(ORIGINAL_LANGUAGES_KEY)
    }

    /** Language tag of the original system order, e.g. "en" / "en-US"; falls back to the live locale. */
    private val systemDefaultTag: String =
        (systemDefault?.firstOrNull() as? String) ?: NSLocale.currentLocale.languageCode

    private val LocalAppLocale = staticCompositionLocalOf { systemDefaultTag }

    // Live since PI2 (2026-07): `NSLocale.preferredLanguages` — the value compose-resources'
    // string resolution reads per access (via compose `Locale.current`, uncached) — reflects the
    // AppleLanguages write within the running process, so the root `key(language)` recomposition
    // re-resolves every string immediately. This OS behavior is load-bearing and pinned by
    // `AppleLanguagesLiveSwitchContractTest` (:composeApp iosSimulatorArm64Test); if a future
    // iOS/CMP version breaks it, that test fails and this flag must flip back to false (the
    // "restart to apply" hint and the pre-strings RTL-flip guard in App.kt both key off it).
    actual val isLiveLocaleSwitchSupported: Boolean = true

    actual val current: String
        @Composable get() = LocalAppLocale.current

    @Composable
    actual infix fun provides(value: String?): ProvidedValue<*> {
        val defaults = NSUserDefaults.standardUserDefaults
        // Guard the persistent AppleLanguages write so repeated/speculative compositions stay
        // idempotent (mirrors the Android/Desktop siblings' Locale.getDefault() guard): only touch
        // NSUserDefaults when the chosen order actually differs from what's already stored.
        val current = defaults.arrayForKey(APPLE_LANGUAGES_KEY)
        return if (value.isNullOrBlank()) {
            if (current != systemDefault) defaults.setObject(systemDefault, APPLE_LANGUAGES_KEY)
            LocalAppLocale.provides(systemDefaultTag)
        } else {
            if (current != listOf(value)) defaults.setObject(listOf(value), APPLE_LANGUAGES_KEY)
            LocalAppLocale.provides(value)
        }
    }
}
