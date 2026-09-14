package me.manga.kira.core.platform

import androidx.lifecycle.Lifecycle
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@LooperMode(LooperMode.Mode.PAUSED)
class ReaderNavigationBarNavigationTest : NavigationBarTestHost() {
    @Test
    fun warmReaderPushKeepsBarsHiddenAfterOutgoingDisposalUntilFinalDeparture() {
        val fixture = openFirstReader()
        beginTransition { fixture.openReader(SECOND) }
        assertReaders(fixture, FIRST, SECOND)
        compose.runOnIdle {
            assertEquals(Lifecycle.State.STARTED, fixture.nav.currentBackStackEntry?.lifecycle?.currentState)
            assertTrue(fixture.disposedReaders.isEmpty())
        }
        finishTransition()
        assertReaders(fixture, SECOND)
        compose.runOnIdle { assertEquals(listOf(FIRST), fixture.disposedReaders) }
        beginTransition { assertTrue(fixture.returnHome()) }
        assertReaders(fixture, SECOND)
        finishTransition()
        assertReaders(fixture)
        compose.runOnIdle { assertEquals(listOf(FIRST, SECOND), fixture.disposedReaders) }
    }

    @Test
    fun popBetweenOverlappingReadersKeepsTheReturningReaderHiddenUntilItsOwnExit() {
        val fixture = openFirstReader()
        compose.runOnUiThread { fixture.openReader(SECOND) }
        compose.waitForIdle()
        assertReaders(fixture, SECOND)
        beginTransition { assertTrue(fixture.nav.popBackStack()) }
        assertReaders(fixture, FIRST, SECOND)
        finishTransition()
        assertReaders(fixture, FIRST)
        compose.runOnIdle { assertEquals(listOf(FIRST, SECOND), fixture.disposedReaders) }
        beginTransition { assertTrue(fixture.nav.popBackStack()) }
        assertReaders(fixture, FIRST)
        finishTransition()
        assertReaders(fixture)
        compose.runOnIdle { assertEquals(listOf(FIRST, SECOND, FIRST), fixture.disposedReaders) }
    }

    private fun openFirstReader(): NavigationBarNavigationFixture {
        val fixture = NavigationBarNavigationFixture()
        show { fixture.Content() }
        compose.runOnUiThread { fixture.openReader(FIRST) }
        compose.waitForIdle()
        assertReaders(fixture, FIRST)
        return fixture
    }

    private fun beginTransition(action: () -> Unit) {
        compose.mainClock.autoAdvance = false
        compose.runOnUiThread(action)
        compose.mainClock.advanceTimeBy(TRANSITION_PROBE_MILLIS)
        compose.waitForIdle()
    }

    private fun finishTransition() {
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
    }

    private fun assertReaders(fixture: NavigationBarNavigationFixture, vararg chapters: String) {
        compose.runOnIdle {
            fixture.assertMounted(chapters.toList())
            NavigationBarWindowProbe(activity.window).assertNavigationVisible(chapters.isEmpty())
            assertNavigationBarRegistrations(activity.window, chapters.size)
        }
    }

    private companion object {
        const val FIRST = "first"
        const val SECOND = "second"
        // Observe the real default transition before it completes; no sleeps or disabled animation.
        const val TRANSITION_PROBE_MILLIS = 64L
    }
}
