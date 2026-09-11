package me.manga.kira.navigation.routes

import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.lifecycle.Lifecycle
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.toRoute
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import me.manga.kira.navigation.Screen
import me.manga.kira.navigation.routes.WhatsNewNavigationFixture.Companion.CURRENT_VERSION
import me.manga.kira.navigation.routes.WhatsNewNavigationFixture.Companion.SEEN_KEY
import me.manga.kira.navigation.safeNavigate
import me.manga.kira.navigation.safePopBackStack
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class, ExperimentalCoroutinesApi::class)
class LibraryWhatsNewRedirectTest {
    @Test
    fun deferredLibraryReturnPushesAndMarksOnceWithoutASeenWriteOrReopen() =
        navigationTest { fixture ->
            val library = runOnIdle { fixture.libraryEntry }
            val gate = runOnIdle { fixture.libraryGate }
            visitAnotherEntryThenPopToStartedLibrary(fixture)
            observeAutomaticMountBeforeTransitionEnd(fixture)
            val notes =
                runOnIdle {
                    assertAutomaticOpen(fixture)
                    fixture.controller.currentBackStackEntry
                }
            runOnUiThread { fixture.redraw++ }
            awaitIdle()
            runOnIdle {
                assertSame(notes, fixture.controller.currentBackStackEntry)
                assertEquals(fixture.redraw, fixture.renderedTick)
                assertEquals(1, fixture.repository.marks)
                assertTrue(fixture.controller.safePopBackStack())
            }
            awaitIdle()
            runOnIdle {
                assertSame(library, fixture.controller.currentBackStackEntry)
                assertSame(gate, fixture.libraryGate)
                assertFalse(gate.shouldShowWhatsNew.value)
                assertEquals("", fixture.prefs.getString(SEEN_KEY))
                assertEquals(1, fixture.repository.marks)
                assertEquals(1, fixture.repository.loads)
            }
        }

    @Test
    fun actualMissingDestinationFailureStaysPendingUntilALaterResume() =
        navigationTest { fixture ->
            val library = runOnIdle { fixture.libraryEntry }
            val gate = runOnIdle { fixture.libraryGate }
            val destination =
                runOnIdle {
                    requireNotNull(fixture.controller.graph.findNode(Screen.WhatsNewScreen::class)).also {
                        fixture.controller.graph.remove(it)
                        fixture.owner.lifecycle.currentState = Lifecycle.State.RESUMED
                    }
                }
            awaitIdle()
            runOnIdle {
                assertSame(library, fixture.controller.currentBackStackEntry)
                assertEquals(Lifecycle.State.RESUMED, library.lifecycle.currentState)
                assertFalse(fixture.controller.tryOpenWhatsNew(library))
                assertPending(fixture)
                fixture.controller.graph.addDestination(destination)
            }
            awaitIdle()
            runOnIdle {
                assertSame(library, fixture.controller.currentBackStackEntry, "Restoring the graph is not a retry loop")
                fixture.owner.lifecycle.currentState = Lifecycle.State.STARTED
            }
            awaitIdle()
            resumeOwner(fixture)
            runOnIdle {
                assertSame(gate, fixture.libraryGate)
                assertAutomaticOpen(fixture)
            }
        }

    @Test
    fun manualDestinationsKeepMountAndUnitNavigationOptions() =
        navigationTest(CURRENT_VERSION) { fixture ->
            resumeOwner(fixture)
            openManualScreenAndRepeatSingleTop(fixture)
            runOnIdle { assertTrue(fixture.controller.safePopBackStack()) }
            awaitIdle()
            runOnIdle { fixture.controller.safeNavigate(Screen.WhatsNewRework) }
            awaitIdle()
            runOnIdle {
                val entry = requireNotNull(fixture.controller.currentBackStackEntry)
                assertTrue(entry.destination.hasRoute<Screen.WhatsNewRework>())
                assertEquals(0, fixture.repository.marks)
                assertEquals(2, fixture.repository.loads)
            }
        }

    private suspend fun ComposeUiTest.openManualScreenAndRepeatSingleTop(fixture: WhatsNewNavigationFixture) {
        runOnIdle {
            val result: Unit = fixture.controller.safeNavigate(Screen.WhatsNewScreen()) { launchSingleTop = true }
            assertEquals(Unit, result)
        }
        awaitIdle()
        val firstId =
            runOnIdle {
                val entry = requireNotNull(fixture.controller.currentBackStackEntry)
                assertFalse(entry.toRoute<Screen.WhatsNewScreen>().isFirstOpen)
                fixture.controller.safeNavigate(Screen.WhatsNewScreen()) { launchSingleTop = true }
                entry.id
            }
        awaitIdle()
        runOnIdle {
            assertEquals(firstId, fixture.controller.currentBackStackEntry?.id)
            assertSame(fixture.libraryEntry, fixture.controller.previousBackStackEntry)
            assertEquals(0, fixture.repository.marks)
            assertEquals(1, fixture.repository.loads)
        }
    }

    private suspend fun ComposeUiTest.visitAnotherEntryThenPopToStartedLibrary(fixture: WhatsNewNavigationFixture) {
        val library =
            runOnIdle {
                assertPending(fixture)
                assertEquals(Lifecycle.State.STARTED, fixture.libraryEntry.lifecycle.currentState)
                assertFalse(fixture.controller.tryOpenWhatsNew(fixture.libraryEntry))
                fixture.controller.navigate(Screen.History)
                fixture.libraryEntry
            }
        awaitIdle()
        resumeOwner(fixture)
        runOnIdle {
            val other = requireNotNull(fixture.controller.currentBackStackEntry)
            assertTrue(other.destination.hasRoute<Screen.History>())
            assertEquals(Lifecycle.State.RESUMED, other.lifecycle.currentState)
            assertFalse(fixture.controller.tryOpenWhatsNew(library))
            assertPending(fixture)
        }
        mainClock.autoAdvance = false
        runOnUiThread { assertTrue(fixture.controller.popBackStack()) }
        mainClock.advanceTimeByFrame()
        awaitIdle()
        runOnIdle {
            assertSame(library, fixture.controller.currentBackStackEntry)
            assertEquals(Lifecycle.State.STARTED, library.lifecycle.currentState)
            assertFalse(fixture.controller.tryOpenWhatsNew(library))
            assertPending(fixture)
        }
    }

    private suspend fun ComposeUiTest.observeAutomaticMountBeforeTransitionEnd(fixture: WhatsNewNavigationFixture) {
        mainClock.advanceTimeUntil(NAVIGATION_TIMEOUT_MILLIS) {
            runOnUiThread { fixture.repository.marks == 1 }
        }
        runOnIdle {
            val entry = requireNotNull(fixture.controller.currentBackStackEntry)
            assertTrue(entry.toRoute<Screen.WhatsNewScreen>().isFirstOpen)
            assertEquals(Lifecycle.State.STARTED, entry.lifecycle.currentState, "Mount mark precedes RESUMED")
        }
        mainClock.autoAdvance = true
        awaitIdle()
    }

    private suspend fun ComposeUiTest.resumeOwner(fixture: WhatsNewNavigationFixture) {
        runOnUiThread { fixture.owner.lifecycle.currentState = Lifecycle.State.RESUMED }
        awaitIdle()
    }

    private fun assertPending(fixture: WhatsNewNavigationFixture) {
        assertTrue(fixture.libraryGate.shouldShowWhatsNew.value)
        assertEquals("", fixture.prefs.getString(SEEN_KEY))
        assertEquals(0, fixture.repository.marks)
        assertEquals(0, fixture.repository.loads, "Constructing the Library gate does not fetch release notes")
    }

    private fun assertAutomaticOpen(fixture: WhatsNewNavigationFixture) {
        val entry = requireNotNull(fixture.controller.currentBackStackEntry)
        assertTrue(entry.destination.hasRoute<Screen.WhatsNewScreen>())
        assertTrue(entry.toRoute<Screen.WhatsNewScreen>().isFirstOpen)
        assertEquals(Lifecycle.State.RESUMED, entry.lifecycle.currentState)
        assertFalse(fixture.libraryGate.shouldShowWhatsNew.value)
        assertEquals("", fixture.prefs.getString(SEEN_KEY))
        assertEquals(1, fixture.repository.marks)
        assertEquals(1, fixture.repository.loads)
    }

    private fun navigationTest(
        seenVersion: String = "",
        block: suspend ComposeUiTest.(WhatsNewNavigationFixture) -> Unit,
    ) = runComposeUiTest {
        try {
            // LifecycleRegistry's synchronous Main.immediate check must start inline on the UI thread.
            runOnUiThread { Dispatchers.setMain(UnconfinedTestDispatcher(mainClock.scheduler)) }
            val fixture = runOnUiThread { WhatsNewNavigationFixture(seenVersion) }
            try {
                setContent { fixture.Content() }
                awaitIdle()
                block(fixture)
            } finally {
                try {
                    runOnUiThread { fixture.mounted = false }
                    mainClock.autoAdvance = true
                    awaitIdle()
                } finally {
                    runOnUiThread { fixture.close() }
                }
            }
        } finally {
            Dispatchers.resetMain()
        }
    }

    private companion object {
        const val NAVIGATION_TIMEOUT_MILLIS = 2_000L
    }
}
