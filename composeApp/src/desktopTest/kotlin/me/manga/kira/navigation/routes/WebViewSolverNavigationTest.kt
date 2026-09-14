package me.manga.kira.navigation.routes

import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.lifecycle.Lifecycle
import androidx.navigation.NavBackStackEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import me.manga.kira.composeapp.generated.resources.Res
import me.manga.kira.composeapp.generated.resources.close
import me.manga.kira.core.webview.KcefState
import me.manga.kira.core.webview.WebViewInitialization
import org.jetbrains.compose.resources.getString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class, ExperimentalCoroutinesApi::class)
class WebViewSolverNavigationTest {
    @Test
    fun unavailablePrecheckAndRefusedNavigationDoNotArmRetry() =
        navigationTest { fixture ->
            val owner = runOnIdle { fixture.currentOwner }
            runOnIdle {
                fixture.available = false
                fixture.solve()
            }
            awaitIdle()
            runOnIdle {
                assertSame(owner, fixture.nav.currentBackStackEntry)
                fixture.available = true
                fixture.host.lifecycle.currentState = Lifecycle.State.STARTED
            }
            awaitIdle()
            runOnIdle { fixture.solve() }
            awaitIdle()
            runOnIdle {
                assertSame(owner, fixture.nav.currentBackStackEntry)
                fixture.host.lifecycle.currentState = Lifecycle.State.RESUMED
            }
            awaitIdle()
            runOnIdle { fixture.manualBrowser() }
            awaitIdle()
            closeBrowser()
            runOnIdle {
                assertSame(owner, fixture.nav.currentBackStackEntry)
                assertTrue(fixture.retries.isEmpty())
            }
        }

    @Test
    fun healthyCloseConsumesBeforeCallbackAndManualVisitCannotRearm() =
        navigationTest { fixture ->
            val owner = runOnIdle { fixture.currentOwner }
            runOnIdle {
                fixture.afterRetry = { assertFalse(WebViewSolverReturn(it).consumeRetry()) }
                fixture.solve()
            }
            awaitIdle()
            closeBrowser()
            runOnIdle {
                assertEquals(1, fixture.retries[owner.id])
                fixture.manualBrowser()
            }
            awaitIdle()
            closeBrowser()
            runOnIdle { fixture.redraw++ }
            awaitIdle()
            runOnIdle {
                assertEquals(fixture.redraw, fixture.renderedTick)
                assertSame(owner, fixture.nav.currentBackStackEntry)
                assertEquals(mapOf(owner.id to 1), fixture.retries)
            }
        }

    @Test
    fun exactOwnerAndInterveningManualBrowserCannotStealSolverReturn() =
        navigationTest { fixture ->
            val firstOwner = runOnIdle { fixture.currentOwner }
            runOnIdle { fixture.solve() }
            awaitIdle()
            val solverBrowser = runOnIdle { fixture.currentBrowser }
            runOnIdle { fixture.manualBrowser() }
            awaitIdle()
            closeBrowser()
            runOnIdle {
                assertSame(solverBrowser, fixture.nav.currentBackStackEntry)
                assertTrue(fixture.retries.isEmpty())
                fixture.solve(firstOwner)
                assertSame(solverBrowser, fixture.nav.currentBackStackEntry)
            }
            closeBrowser()
            exerciseSecondOwner(fixture, firstOwner)
        }

    @Test
    fun consumedFailureDoesNotPoisonLaterHealthyAttempt() =
        navigationTest { fixture ->
            runOnIdle {
                fixture.nextInitialization = WebViewInitialization.Unavailable
                fixture.solve()
            }
            awaitIdle()
            closeBrowser()
            runOnIdle {
                assertTrue(fixture.retries.isEmpty())
                fixture.nextInitialization = WebViewInitialization.Ready
                fixture.solve()
            }
            awaitIdle()
            closeBrowser()
            runOnIdle { assertEquals(mapOf(fixture.currentOwner.id to 1), fixture.retries) }
        }

    private suspend fun ComposeUiTest.exerciseSecondOwner(
        fixture: WebViewSolverNavigationFixture,
        first: NavBackStackEntry,
    ) {
        runOnIdle { fixture.nav.navigate(WebViewSolverTestOwner("second")) }
        awaitIdle()
        val second = runOnIdle { fixture.currentOwner }
        runOnIdle {
            assertNotEquals(first.id, second.id)
            fixture.solve(first)
            assertSame(second, fixture.nav.currentBackStackEntry)
            fixture.solve(second)
        }
        awaitIdle()
        closeBrowser()
        runOnIdle {
            assertSame(second, fixture.nav.currentBackStackEntry)
            assertEquals(mapOf(first.id to 1, second.id to 1), fixture.retries)
        }
    }

    private suspend fun ComposeUiTest.closeBrowser() {
        onNodeWithContentDescription(getString(Res.string.close)).performClick()
        awaitIdle()
    }

    private fun navigationTest(block: suspend ComposeUiTest.(WebViewSolverNavigationFixture) -> Unit) =
        runComposeUiTest {
            try {
                runOnUiThread { Dispatchers.setMain(UnconfinedTestDispatcher(mainClock.scheduler)) }
                assertFalse(KcefState.initialized.value, "This fixture must not acquire a native browser")
                val fixture = runOnUiThread { WebViewSolverNavigationFixture() }
                try {
                    setContent { fixture.Content() }
                    awaitIdle()
                    block(fixture)
                } finally {
                    try {
                        runOnUiThread { fixture.mounted = false }
                        awaitIdle()
                    } finally {
                        runOnUiThread { fixture.close() }
                    }
                }
            } finally {
                Dispatchers.resetMain()
            }
        }
}
