package me.manga.kira.locale

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ProvidedValue

/**
 * Cross-platform app-language override for compose-resources.
 *
 * `stringResource` resolves against the *platform* locale. The per-platform `LocaleSwitcher` only
 * changes that on Android (activity recreate); on iOS/Desktop it is a no-op, so selecting a language
 * in Settings persisted the choice but never changed the UI language. Providing this at the app root
 * (keyed on the persisted language code so the subtree recomposes) re-resolves strings in the chosen
 * language on every platform:
 *  - **Android** — overrides `LocalConfiguration`'s locale (which compose-resources reads).
 *  - **Desktop (JVM)** — `Locale.setDefault(...)`; the keyed recomposition re-reads it.
 *  - **iOS** — sets `NSUserDefaults["AppleLanguages"]`; `NSLocale.preferredLanguages` (what
 *    compose-resources string resolution reads, uncached) reflects the write within the running
 *    process, so the keyed recomposition switches live (PI2 — pinned by
 *    `AppleLanguagesLiveSwitchContractTest`). Swift-side `NSBundle` strings catch up on relaunch.
 *
 * `provides(null)` (or a blank code) restores the original system default.
 */
expect object LocalAppLocale {
    val current: String @Composable get
    @Composable infix fun provides(value: String?): ProvidedValue<*>

    /**
     * Whether an in-app language change re-resolves `stringResource` text *within the running
     * session*. `true` on all three targets since PI2 (2026-07): Android (LocalConfiguration
     * override), Desktop (`Locale.setDefault`), and iOS (`AppleLanguages` writes are visible to
     * `NSLocale.preferredLanguages` — and thus to compose-resources — within the process; pinned
     * by `AppleLanguagesLiveSwitchContractTest`). The seam is kept (rather than deleting the
     * flag) because the iOS behavior is OS-provided, not API-guaranteed: if a future iOS/CMP
     * version breaks it, flip the iOS actual back to `false` and the "restart to apply" hint plus
     * App.kt's pre-strings RTL-flip guard reactivate without further changes.
     */
    val isLiveLocaleSwitchSupported: Boolean
}
