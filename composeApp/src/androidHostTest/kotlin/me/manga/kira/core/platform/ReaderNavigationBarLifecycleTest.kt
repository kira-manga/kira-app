package me.manga.kira.core.platform

import android.content.Context
import android.content.ContextWrapper
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import kotlin.test.assertEquals
import kotlin.test.assertNotSame

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@LooperMode(LooperMode.Mode.PAUSED)
class ReaderNavigationBarLifecycleTest : NavigationBarTestHost() {
    @Test
    fun resumeAndWindowFocusReapplyAnExistingReader() {
        openEffect()
        val probe = compose.runOnUiThread { NavigationBarWindowProbe(activity.window) }
        pauseHost()
        compose.runOnUiThread {
            probe.showNavigation()
            probe.assertNavigationVisible(true)
        }
        resumeHost()
        compose.runOnIdle { probe.assertNavigationVisible(false) }
        focusHost(false)
        compose.runOnUiThread {
            probe.showNavigation()
            probe.assertNavigationVisible(true)
        }
        focusHost(true)
        compose.runOnIdle {
            probe.assertNavigationVisible(false)
            assertNavigationBarRegistrations(activity.window, 1)
        }
    }

    @Test
    fun disposedReaderDoesNotRehideOnLaterHostCallbacks() {
        val fixture = openEffect()
        compose.runOnUiThread { fixture.mounted = false }
        compose.waitForIdle()
        focusHost(false)
        focusHost(true)
        pauseHost()
        resumeHost()
        compose.runOnIdle {
            NavigationBarWindowProbe(activity.window).assertNavigationVisible(true)
            assertNavigationBarRegistrations(activity.window, 0)
        }
    }

    @Test
    fun contextWindowChangeRebindsEvenWhenTheEntryLifecycleIsUnchanged() {
        val other = siblingHost()
        val entry = compose.runOnUiThread { NavigationBarEntryOwner() }
        val fixture = openEffect(ContextWrapper(activity), entry)
        compose.runOnUiThread { fixture.context = ContextWrapper(other.get()) }
        compose.waitForIdle()
        focusHost(false)
        focusHost(true)
        compose.runOnIdle {
            NavigationBarWindowProbe(activity.window).assertNavigationVisible(true)
            NavigationBarWindowProbe(other.get().window).assertNavigationVisible(false)
            assertNavigationBarRegistrations(activity.window, 0)
            assertNavigationBarRegistrations(other.get().window, 1)
            assertEquals(1, entry.lifecycle.observerCount)
            fixture.mounted = false
        }
        compose.waitForIdle()
        compose.runOnIdle {
            NavigationBarWindowProbe(other.get().window).assertNavigationVisible(true)
            assertEquals(0, entry.lifecycle.observerCount)
        }
    }

    @Test
    fun lifecycleReplacementUnregistersTheOldEntryWithoutRetargetingItsCallbacks() {
        val first = compose.runOnUiThread { NavigationBarEntryOwner() }
        val second = compose.runOnUiThread { NavigationBarEntryOwner() }
        val fixture = openEffect(entry = first)
        val probe = compose.runOnUiThread { NavigationBarWindowProbe(activity.window) }
        compose.runOnUiThread {
            first.lifecycle.currentState = Lifecycle.State.STARTED
            fixture.entry = second
        }
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals(0, first.lifecycle.observerCount)
            assertEquals(1, second.lifecycle.observerCount)
            probe.showNavigation()
            first.lifecycle.currentState = Lifecycle.State.RESUMED
            probe.assertNavigationVisible(true)
            second.lifecycle.currentState = Lifecycle.State.STARTED
            second.lifecycle.currentState = Lifecycle.State.RESUMED
            probe.assertNavigationVisible(false)
            assertNavigationBarRegistrations(activity.window, 1)
        }
    }

    @Test
    fun entryDestructionReleasesBeforeCompositionDisposalAndDisposalIsIdempotent() {
        val entry = compose.runOnUiThread { NavigationBarEntryOwner() }
        val fixture = openEffect(entry = entry)
        compose.runOnUiThread { entry.lifecycle.currentState = Lifecycle.State.DESTROYED }
        compose.runOnIdle {
            NavigationBarWindowProbe(activity.window).assertNavigationVisible(true)
            assertNavigationBarRegistrations(activity.window, 0)
            assertEquals(0, entry.lifecycle.observerCount)
            fixture.mounted = false
        }
        compose.waitForIdle()
        focusHost(false)
        focusHost(true)
        compose.runOnIdle { NavigationBarWindowProbe(activity.window).assertNavigationVisible(true) }
    }

    @Test
    fun activityRecreationReleasesTheOldWindowAndAcquiresTheNewWindow() {
        openEffect()
        val oldWindow = activity.window
        val oldLifecycle = activity.lifecycle
        val oldProbe = compose.runOnUiThread { NavigationBarWindowProbe(oldWindow) }
        recreateHost()
        compose.runOnIdle {
            assertNotSame(oldWindow, activity.window)
            assertEquals(Lifecycle.State.DESTROYED, oldLifecycle.currentState)
            oldProbe.assertNavigationVisible(true)
            assertNavigationBarRegistrations(oldWindow, 0)
        }
        openEffect()
        compose.runOnIdle {
            NavigationBarWindowProbe(activity.window).assertNavigationVisible(false)
            assertNavigationBarRegistrations(activity.window, 1)
            oldProbe.assertNavigationVisible(true)
        }
    }

    @Test
    fun missingActivityIsInertAndLaterResolvedWindowCanAcquire() {
        val fixture = openEffect(RuntimeEnvironment.getApplication())
        compose.runOnIdle {
            NavigationBarWindowProbe(activity.window).assertNavigationVisible(true)
            assertNavigationBarRegistrations(activity.window, 0)
            fixture.context = ContextWrapper(activity)
        }
        compose.waitForIdle()
        compose.runOnIdle {
            NavigationBarWindowProbe(activity.window).assertNavigationVisible(false)
            assertNavigationBarRegistrations(activity.window, 1)
        }
    }

    private fun openEffect(
        context: Context = activity,
        entry: LifecycleOwner = activity,
    ): NavigationBarEffectFixture {
        val fixture = NavigationBarEffectFixture(context, entry)
        show { fixture.Content() }
        return fixture
    }
}
