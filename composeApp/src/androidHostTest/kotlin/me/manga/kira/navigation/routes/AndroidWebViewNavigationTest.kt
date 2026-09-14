package me.manga.kira.navigation.routes

import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.Lifecycle
import me.manga.kira.composeapp.generated.resources.Res
import me.manga.kira.composeapp.generated.resources.back
import me.manga.kira.composeapp.generated.resources.close
import me.manga.kira.composeapp.generated.resources.webview_action_forward
import me.manga.kira.composeapp.generated.resources.webview_action_reload
import me.manga.kira.composeapp.generated.resources.webview_action_save_headers
import me.manga.kira.composeapp.generated.resources.webview_open_external_browser
import me.manga.kira.composeapp.generated.resources.webview_unavailable_retry
import me.manga.kira.core.webview.AndroidWebViewComposeTest
import me.manga.kira.core.webview.AndroidWebViewController
import me.manga.kira.core.webview.WebViewInitialization
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import kotlin.test.assertEquals
import kotlin.test.assertSame

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@LooperMode(LooperMode.Mode.PAUSED)
class AndroidWebViewNavigationTest : AndroidWebViewComposeTest() {
    @Test
    fun failedInitializationToolbarCloseSuppressesRetryAndSave() {
        val fixture = openFixture()
        assertUnavailableControls()
        closeToolbar()
        compose.runOnIdle { fixture.assertReturned(0, 0) }
    }

    @Test
    fun failedInitializationSystemBackSuppressesRetryAndSave() {
        val fixture = openFixture()
        systemBack()
        compose.runOnIdle { fixture.assertReturned(0, 0) }
    }

    @Test
    fun initializingToolbarAndSystemBackNeverCountAsSolved() {
        val fixture = openFixture(initializing = true)
        assertUnavailableControls()
        closeToolbar()
        compose.runOnIdle {
            fixture.assertReturned(0, 0)
            fixture.open()
        }
        compose.waitForIdle()
        systemBack()
        compose.runOnIdle { fixture.assertReturned(0, 0) }
    }

    @Test
    fun refusedFailureCloseThenRealRetryAndHealthyCloseRetainsOneRetry() {
        val fixture = openFixture()
        pauseHost()
        compose.runOnIdle { assertEquals(Lifecycle.State.STARTED, fixture.browserEntry.lifecycle.currentState) }
        closeToolbar()
        compose.runOnIdle {
            assertSame(fixture.browserEntry, fixture.nav.currentBackStackEntry)
            assertEquals(0, fixture.retries)
            assertEquals(0, fixture.saves)
            fixture.constructorFailure = null
        }
        resumeHost()
        compose.onNodeWithText(label(Res.string.webview_unavailable_retry)).performClick()
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals(WebViewInitialization.Ready, fixture.browserController.state.value.initialization)
            assertEquals(2, fixture.probes)
            assertEquals(2, fixture.allocations)
        }
        closeToolbar()
        compose.runOnIdle { fixture.assertReturned(1, 1) }
    }

    @Test
    fun externalBrowserResumeDoesNotReturnToOwnerOrRetrySolver() {
        val fixture = openFixture()
        compose.onNodeWithText(label(Res.string.webview_open_external_browser)).performClick()
        pauseHost()
        resumeHost()
        compose.runOnIdle {
            assertSame(fixture.browserEntry, fixture.nav.currentBackStackEntry)
            assertEquals(1, fixture.externalOpens)
            assertEquals(0, fixture.retries)
            assertEquals(1, fixture.probes)
            assertEquals(1, fixture.allocations)
        }
        systemBack()
        compose.runOnIdle { fixture.assertReturned(0, 0) }
    }

    @Test
    fun healthySystemBackUsesHistoryBeforeOneNormalCloseRetry() {
        val fixture = openFixture(healthy = true)
        compose.runOnIdle {
            val view = fixture.views.single()
            view.hasHistory = true
            (fixture.browserController as AndroidWebViewController).onLoadingChanged(false, view)
        }
        systemBack()
        compose.runOnIdle {
            assertSame(fixture.browserEntry, fixture.nav.currentBackStackEntry)
            assertEquals(1, fixture.views.single().backCalls)
            (fixture.browserController as AndroidWebViewController).onLoadingChanged(false, fixture.views.single())
        }
        systemBack()
        compose.runOnIdle { fixture.assertReturned(1, 1) }
    }

    private fun openFixture(
        initializing: Boolean = false,
        healthy: Boolean = false,
    ): AndroidWebViewNavigationFixture {
        val fixture =
            AndroidWebViewNavigationFixture().apply {
                initializingController = initializing
                if (healthy) constructorFailure = null
            }
        show { fixture.Content() }
        compose.runOnIdle { fixture.open() }
        compose.waitForIdle()
        return fixture
    }

    private fun assertUnavailableControls() {
        listOf(
            Res.string.back,
            Res.string.webview_action_forward,
            Res.string.webview_action_reload,
            Res.string.webview_action_save_headers,
        ).forEach {
            compose.onNodeWithContentDescription(label(it)).assertIsNotEnabled()
        }
    }

    private fun closeToolbar() {
        compose.onNodeWithContentDescription(label(Res.string.close)).performClick()
        compose.waitForIdle()
    }

    private fun systemBack() {
        compose.runOnUiThread { activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
    }
}
