package me.manga.kira.core.platform

import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@LooperMode(LooperMode.Mode.PAUSED)
class ReaderNavigationBarOwnerTest : NavigationBarTestHost() {
    @Test
    fun duplicateAndLateReleaseCannotReleaseAnotherReader() {
        compose.runOnUiThread {
            val window = activity.window
            val probe = NavigationBarWindowProbe(window)
            val first = ReaderNavigationBarOwner(window, activity.lifecycle)
            val second = ReaderNavigationBarOwner(window, activity.lifecycle)
            assertNavigationBarRegistrations(window, 2)
            first.release()
            first.release()
            probe.assertNavigationVisible(false)
            assertNavigationBarRegistrations(window, 1)
            second.release()
            probe.assertNavigationVisible(true)
            val next = ReaderNavigationBarOwner(window, activity.lifecycle)
            second.release()
            probe.assertNavigationVisible(false)
            next.release()
            probe.assertNavigationVisible(true)
            assertNavigationBarRegistrations(window, 0)
        }
    }

    @Test
    fun finalReleaseRestoresOnlyNavigationAndRetainsDefaultBehavior() {
        compose.runOnUiThread {
            val probe = NavigationBarWindowProbe(activity.window)
            probe.hideStatus()
            probe.behavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            val owner = ReaderNavigationBarOwner(activity.window, activity.lifecycle)
            probe.assertNavigationVisible(false)
            probe.assertStatusVisible(false)
            assertEquals(WindowInsetsControllerCompat.BEHAVIOR_DEFAULT, probe.behavior)
            owner.release()
            probe.assertNavigationVisible(true)
            probe.assertStatusVisible(false)
            assertEquals(WindowInsetsControllerCompat.BEHAVIOR_DEFAULT, probe.behavior)
        }
    }

    @Test
    fun twoWindowsHaveIndependentFinalRelease() {
        val other = siblingHost()
        compose.runOnUiThread {
            val firstProbe = NavigationBarWindowProbe(activity.window)
            val secondProbe = NavigationBarWindowProbe(other.get().window)
            val first = ReaderNavigationBarOwner(activity.window, activity.lifecycle)
            val second = ReaderNavigationBarOwner(other.get().window, other.get().lifecycle)
            first.release()
            firstProbe.assertNavigationVisible(true)
            secondProbe.assertNavigationVisible(false)
            second.release()
            firstProbe.assertNavigationVisible(true)
            secondProbe.assertNavigationVisible(true)
            assertNavigationBarRegistrations(activity.window, 0)
            assertNavigationBarRegistrations(other.get().window, 0)
        }
    }

    @Test
    fun destroyedEntryCannotAcquireOrLeaveWindowCallbacks() {
        compose.runOnUiThread {
            val entry = NavigationBarEntryOwner()
            entry.lifecycle.currentState = Lifecycle.State.DESTROYED
            val probe = NavigationBarWindowProbe(activity.window)
            val owner = ReaderNavigationBarOwner(activity.window, entry.lifecycle)
            owner.release()
            probe.assertNavigationVisible(true)
            assertNavigationBarRegistrations(activity.window, 0)
            assertEquals(0, entry.lifecycle.observerCount)
        }
    }

    @Test
    fun destroyingOneEntryKeepsTheOtherLeaseUntilItsOwnRelease() {
        compose.runOnUiThread {
            val entry = NavigationBarEntryOwner()
            val probe = NavigationBarWindowProbe(activity.window)
            val first = ReaderNavigationBarOwner(activity.window, entry.lifecycle)
            val second = ReaderNavigationBarOwner(activity.window, activity.lifecycle)
            entry.lifecycle.currentState = Lifecycle.State.DESTROYED
            first.release()
            probe.assertNavigationVisible(false)
            assertNavigationBarRegistrations(activity.window, 1)
            assertEquals(0, entry.lifecycle.observerCount)
            second.release()
            probe.assertNavigationVisible(true)
            assertNavigationBarRegistrations(activity.window, 0)
        }
    }

    @Test
    fun reattachingTheSameWindowReappliesTheOutstandingLease() {
        val owner = compose.runOnUiThread {
            val window = activity.window
            val decor = window.decorView
            val owner = ReaderNavigationBarOwner(window, activity.lifecycle)
            NavigationBarWindowProbe(window).showNavigation()
            activity.windowManager.removeViewImmediate(decor)
            assertFalse(decor.isAttachedToWindow)
            activity.windowManager.addView(decor, window.attributes)
            owner
        }
        // WindowManager queues the first traversal; attachment is not synchronous with addView.
        compose.waitForIdle()
        compose.runOnUiThread {
            val window = activity.window
            assertTrue(window.decorView.isAttachedToWindow)
            val reattached = NavigationBarWindowProbe(window)
            reattached.assertNavigationVisible(false)
            assertNavigationBarRegistrations(window, 1)
            owner.release()
            reattached.assertNavigationVisible(true)
            assertNavigationBarRegistrations(window, 0)
        }
    }
}
