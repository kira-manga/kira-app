package me.manga.kira.locale

import android.content.Context
import android.content.ContextWrapper
import android.content.res.Configuration
import android.content.res.Resources
import android.os.LocaleList
import android.view.View
import com.russhwolf.settings.ObservableSettings
import java.util.Locale
import me.manga.kira.MainActivity
import me.manga.kira.MyApp
import me.manga.kira.R
import me.manga.kira.platform.locale.AndroidLocaleState
import me.manga.kira.platform.locale.AndroidResourceLocales
import me.manga.kira.platform.storage.DataStoreHelper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.GlobalContext
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.annotation.experimental.LazyApplication
import org.robolectric.annotation.experimental.LazyApplication.LazyLoad.ON
import org.robolectric.shadow.api.Shadow

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32, 35], application = MyApp::class, qualifiers = "en-rUS")
@LooperMode(LooperMode.Mode.PAUSED)
@LazyApplication(ON)
class ActualHostResourceLocaleTest : RealApplicationLocaleTest() {
    @Test
    @Config(shadows = [PreKoinLocaleApplicationShadow::class])
    fun persistedLanguageIsVisibleAtTheRealFirstAttachResourceBeforeKoin() {
        val host = app
        val shadow = Shadow.extract<PreKoinLocaleApplicationShadow>(host)
        val witness = checkNotNull(shadow.witness)
        assertSame(host, witness.host)
        assertTrue(witness.koinWasAbsent)
        assertEquals("en-US", witness.rawConfiguration.locales.toLanguageTags())
        assertEquals("ar", witness.localizedConfiguration.locales.toLanguageTags())
        assertEquals(View.LAYOUT_DIRECTION_RTL, witness.localizedConfiguration.layoutDirection)
        assertEquals("تحديث المكتبة", witness.firstResourceText)
        assertEquals("ar", host.androidLocaleState.dataStore.currentLanguage())
        assertRealKoinOwner()
        // Robolectric subsequently resets this returned Resources before onCreate. This witness
        // proves actual pre-Koin attach, NOT that post-reset startup consumers have been validated.
    }

    @Test
    fun actualActivityResolvesBeforeSplashAndChangesResourcesWithoutReplacingTheHost() {
        selectLanguage("ar") // Bootstrap the real MyApp first with its unselected baseline.
        val koin = GlobalContext.get()
        Robolectric.buildActivity(MainActivity::class.java).use { controller ->
            val activity = controller.get()
            assertEquals("تحديث المكتبة", activity.getString(R.string.notification_channel_library_refresh))
            assertSame(app, activity.applicationContext)
            assertSame(app, activity.application)
            controller.create() // Runs the actual splash/theme/onCreate/setContent path.
            val content = checkNotNull(activity.findViewById<View>(android.R.id.content))
            selectLanguage("fr")
            assertEquals(
                "Mise à jour de la bibliothèque", activity.getString(R.string.notification_channel_library_refresh),
            )
            assertEquals(
                "Mise à jour de la bibliothèque", app.getString(R.string.notification_channel_library_refresh),
            )
            assertSame(activity, controller.get())
            assertSame(content, activity.findViewById<View>(android.R.id.content))
            assertFalse(activity.isFinishing)
            assertSame(koin, GlobalContext.get())
            assertRealKoinOwner()
        }
    }

    @Test
    fun blankUsesTheFullCurrentSystemListAndDirectionNotTheJvmDefault() {
        selectLanguage(" \t")
        val arabicFirst = LocaleList(Locale.forLanguageTag("ar-EG"), Locale.FRANCE)
        setSystemLocales(arabicFirst)
        Locale.setDefault(Locale.JAPAN)
        Robolectric.buildActivity(MainActivity::class.java).use { controller ->
            val activity = controller.get()
            for (host in listOf(app, activity)) {
                assertEquals(arabicFirst, host.resources.configuration.locales)
                assertEquals(View.LAYOUT_DIRECTION_RTL, host.resources.configuration.layoutDirection)
                assertEquals("تحديث المكتبة", host.getString(R.string.notification_channel_library_refresh))
            }
            val frenchFirst = LocaleList(Locale.FRANCE, Locale.US)
            setSystemLocales(frenchFirst)
            for (host in listOf(app, activity)) {
                assertEquals(frenchFirst, host.resources.configuration.locales)
                assertEquals(View.LAYOUT_DIRECTION_LTR, host.resources.configuration.layoutDirection)
                assertEquals(
                    "Mise à jour de la bibliothèque", host.getString(R.string.notification_channel_library_refresh),
                )
            }
            assertEquals(Locale.JAPAN, Locale.getDefault())
        }
    }

    @Test
    fun explicitTagsStaySingleAndLenientIncludingDefaultResourceFallback() {
        selectLanguage("")
        val cases = listOf(
            Triple("fr", "fr", "Mise à jour de la bibliothèque"),
            Triple("zz-ZZ", "zz-ZZ", "Library refresh updates"),
            Triple("und", "und", "Library refresh updates"),
            Triple("fr_FR", "und", "Library refresh updates"),
        )
        Robolectric.buildActivity(MainActivity::class.java).use { controller ->
            for ((savedTag, resourceTag, text) in cases) {
                selectLanguage(savedTag)
                for (host in listOf(app, controller.get())) {
                    assertEquals(1, host.resources.configuration.locales.size())
                    assertEquals(resourceTag, host.resources.configuration.locales.toLanguageTags())
                    assertEquals(text, host.getString(R.string.notification_channel_library_refresh))
                }
                assertEquals(savedTag, app.androidLocaleState.dataStore.currentLanguage())
            }
        }
    }

    @Test
    fun eachActualHostUsesItsCurrentRawBaseAndPreservesNonLocaleConfiguration() {
        selectLanguage("ar")
        Robolectric.buildActivity(MainActivity::class.java).use { controller ->
            verifyCurrentRawConfiguration(app)
            verifyCurrentRawConfiguration(controller.get())
        }
    }

    @Test
    fun emptySystemListHasADeterministicNonJvmFallback() {
        selectLanguage("")
        // Simulate the empty service result directly; updateConfiguration normalizes empty lists.
        Resources.getSystem().configuration.setLocales(LocaleList.getEmptyLocaleList())
        Locale.setDefault(Locale.forLanguageTag("ar"))
        assertEquals("en", app.resources.configuration.locales.toLanguageTags())
        assertEquals("Library refresh updates", app.getString(R.string.notification_channel_library_refresh))
    }

    private fun assertRealKoinOwner() {
        val koin = GlobalContext.get()
        val state = app.androidLocaleState
        assertSame(app, koin.get<Context>())
        assertSame(state, koin.get<AndroidLocaleState>())
        assertSame(state.settings, koin.get<ObservableSettings>())
        assertSame(state.dataStore, koin.get<DataStoreHelper>())
        assertSame(state.resourceLocales, koin.get<AndroidResourceLocales>())
    }

    private fun verifyCurrentRawConfiguration(host: ContextWrapper) {
        val rawResources = host.baseContext.resources
        val original = Configuration(rawResources.configuration)
        try {
            for (night in listOf(Configuration.UI_MODE_NIGHT_YES, Configuration.UI_MODE_NIGHT_NO)) {
                val next = Configuration(original).apply {
                    setLocales(LocaleList(Locale.US))
                    fontScale = if (night == Configuration.UI_MODE_NIGHT_YES) 1.25f else 1.75f
                    uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or night
                    densityDpi = if (night == Configuration.UI_MODE_NIGHT_YES) 240 else 320
                    screenWidthDp = 701
                    screenHeightDp = 903
                }
                @Suppress("DEPRECATION")
                rawResources.updateConfiguration(next, rawResources.displayMetrics)
                val expected = Configuration(rawResources.configuration).apply {
                    setLocales(LocaleList(Locale.forLanguageTag("ar")))
                }
                assertEquals(expected, host.resources.configuration)
                assertEquals(next, rawResources.configuration)
                assertSame(app, host.applicationContext)
            }
        } finally {
            @Suppress("DEPRECATION")
            rawResources.updateConfiguration(original, rawResources.displayMetrics)
        }
    }
}
