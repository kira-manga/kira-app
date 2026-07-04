package me.manga.kira.platform.version

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * #20 — DesktopAppVersionProvider resolves a real version from the `kira.app.version` JVM property
 * (set by desktopApp's run config), falling back to a stable constant when nothing supplies one.
 */
class DesktopAppVersionProviderTest {

    @AfterTest
    fun clearProperty() {
        System.clearProperty("kira.app.version")
    }

    @Test
    fun readsInjectedSystemProperty() {
        System.setProperty("kira.app.version", "9.9.9")
        // versionName is resolved at construction, so set the property first.
        assertEquals("9.9.9", DesktopAppVersionProvider().versionName)
    }

    @Test
    fun fallsBackWhenUnset() {
        System.clearProperty("kira.app.version")
        // No property and (in the test JVM) no manifest Implementation-Version → the constant.
        // Assert it is a non-blank stable string rather than pinning the exact fallback, so a
        // future manifest-supplied version in CI doesn't make this brittle.
        assertTrue(DesktopAppVersionProvider().versionName.isNotBlank())
    }
}
