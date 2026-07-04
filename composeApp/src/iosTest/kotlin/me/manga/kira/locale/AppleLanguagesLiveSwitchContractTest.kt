package me.manga.kira.locale

import androidx.compose.ui.text.intl.Locale
import org.jetbrains.compose.resources.getSystemResourceEnvironment
import platform.Foundation.NSLocale
import platform.Foundation.NSUserDefaults
import platform.Foundation.preferredLanguages
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Pins the OS behavior `LocalAppLocale.isLiveLocaleSwitchSupported = true` depends on (PI2,
 * 2026-07): a same-process `NSUserDefaults["AppleLanguages"]` write must be visible to every
 * locale read on the compose-resources string-resolution path —
 *  1. `NSLocale.preferredLanguages` (what compose ui-text's darwin delegate reads per access,
 *     with no compose-side cache),
 *  2. compose `Locale.current` (what `stringResource`'s ComposeEnvironment keys its
 *     `remember(composeLocale, …)` on),
 *  3. `getSystemResourceEnvironment()` (the non-composable resolution entry point).
 *
 * This is OS-provided behavior, not API-guaranteed: if a future iOS/CMP version stops reflecting
 * the write in-process, THIS TEST FAILS — flip the iOS `isLiveLocaleSwitchSupported` actual back
 * to `false` (the "restart to apply" hint and App.kt's pre-strings RTL-flip guard reactivate
 * automatically; see `LocalAppLocale.ios.kt`).
 *
 * Empirical record from the first run (iOS 26 simulator, CMP 1.11.1):
 * `preferredLanguages [en-EG, ar-EG] → [ar]`, compose `Locale.current en-EG → ar`, resource
 * environment moved — all three within the same process, no relaunch.
 */
class AppleLanguagesLiveSwitchContractTest {

    @Test
    fun appleLanguagesWrite_isVisibleInProcess_toTheStringResolutionPath() {
        val defaults = NSUserDefaults.standardUserDefaults
        val original = defaults.arrayForKey("AppleLanguages")
        try {
            val beforeCompose = Locale.current.toLanguageTag()
            val beforeEnv = getSystemResourceEnvironment()

            // Pick a target guaranteed to differ from the host machine's language order.
            val target = if (beforeCompose.startsWith("ar")) "ja" else "ar"
            defaults.setObject(listOf(target), "AppleLanguages")

            assertEquals(
                target,
                NSLocale.preferredLanguages.firstOrNull() as? String,
                "preferredLanguages must reflect the AppleLanguages write within the process",
            )
            assertEquals(
                target,
                Locale.current.toLanguageTag(),
                "compose Locale.current (the stringResource environment key) must move in-process",
            )
            assertNotEquals(
                beforeEnv,
                getSystemResourceEnvironment(),
                "the compose-resources environment must move so strings re-resolve",
            )
            assertTrue(beforeCompose != target, "probe sanity: the target really differed")
        } finally {
            if (original != null) {
                defaults.setObject(original, "AppleLanguages")
            } else {
                defaults.removeObjectForKey("AppleLanguages")
            }
        }
    }
}
