package me.manga.kira.core.webview

import android.net.Uri
import android.util.AndroidRuntimeException
import android.webkit.WebResourceRequest
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import me.manga.kira.composeapp.generated.resources.Res
import me.manga.kira.composeapp.generated.resources.webview_external_browser_failed
import me.manga.kira.composeapp.generated.resources.webview_open_external_browser
import me.manga.kira.composeapp.generated.resources.webview_unavailable_retry
import me.manga.kira.composeapp.generated.resources.webview_unavailable_title
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@LooperMode(LooperMode.Mode.PAUSED)
class AndroidWebViewHostTest : AndroidWebViewComposeTest() {
    @Test
    fun positiveProbeConstructorFailureSurvivesRecompositionThenRetryCreatesOneReadyView() {
        val fixture = AndroidWebViewHostFixture().apply { constructorFailure = UnsupportedOperationException() }
        show { fixture.Content() }
        compose.runOnIdle {
            fixture.assertUnavailable(1)
            assertTrue(fixture.views.isEmpty())
        }
        compose.onNodeWithText(label(Res.string.webview_unavailable_title)).assertIsDisplayed()
        compose.runOnUiThread {
            fixture.redraw++
            fixture.url = "$WEBVIEW_TEST_URL/later"
        }
        compose.waitForIdle()
        compose.runOnIdle {
            fixture.assertUnavailable(1)
            assertEquals(fixture.redraw, fixture.renderedTick)
            fixture.constructorFailure = null
        }
        compose.onNodeWithText(label(Res.string.webview_unavailable_retry)).performClick()
        compose.waitForIdle()
        compose.runOnIdle { fixture.assertReady(2) }
        compose.runOnUiThread { fixture.visible = false }
        compose.waitForIdle()
        compose.runOnIdle { assertReleasedOnce(fixture.views.single()) }
    }

    @Test
    fun partialSetupFailureNeverAttachesOrCapturesAndReleasesOnce() {
        val fixture =
            AndroidWebViewHostFixture().apply {
                setupFailure = AndroidRuntimeException("setup unavailable")
                fireDuringSetup = true
            }
        show { fixture.Content() }
        compose.runOnIdle {
            fixture.assertUnavailable(1)
            assertReleasedOnce(fixture.views.single())
        }
        compose.runOnUiThread { fixture.visible = false }
        compose.waitForIdle()
        compose.runOnIdle { assertReleasedOnce(fixture.views.single()) }
    }

    @Test
    fun retainedHostUsesLatestUrlUaCallbacksAndGateThenFencesDisposedClients() {
        val fixture = AndroidWebViewHostFixture()
        show { fixture.Content() }
        compose.runOnUiThread {
            fixture.url = "$WEBVIEW_TEST_URL/later"
            fixture.userAgent = "$WEBVIEW_TEST_UA-updated"
            fixture.redraw++
            fixture.navigationAllowed = false
        }
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals(1, fixture.allocations)
            assertTrue(
                fixture.clients
                    .single()
                    .navigation
                    .shouldOverrideUrlLoading(fixture.views.single(), Request()),
            )
            fixture.finishPage()
            fixture.assertLatestCapture()
        }
        compose.runOnUiThread { fixture.visible = false }
        compose.waitForIdle()
        assertDisposedClientsAreInert(fixture)
    }

    @Test
    fun applicationCallbackFailureIsNotMappedToProviderUnavailability() {
        val failure = UnsupportedOperationException("application callback")
        val fixture = AndroidWebViewHostFixture().apply { callbackFailure = failure }
        show { fixture.Content() }
        compose.runOnIdle {
            assertSame(failure, assertFailsWith<UnsupportedOperationException> { fixture.finishPage() })
            assertEquals(WebViewInitialization.Ready, fixture.controller.state.value.initialization)
            assertEquals(0, fixture.views.single().destroyCalls)
        }
    }

    @Test
    fun externalLaunchFailureIsVisibleAndBrowserResumeDoesNotRetryInitialization() {
        val fixture = AndroidWebViewHostFixture().apply { constructorFailure = UnsupportedOperationException() }
        show { fixture.Content() }
        compose.onNodeWithText(label(Res.string.webview_open_external_browser)).performClick()
        compose.onNodeWithText(label(Res.string.webview_external_browser_failed)).assertIsDisplayed()
        compose.runOnIdle {
            fixture.assertUnavailable(1)
            fixture.externalSucceeds = true
        }
        compose.onNodeWithText(label(Res.string.webview_open_external_browser)).performClick()
        compose.onNodeWithText(label(Res.string.webview_external_browser_failed)).assertDoesNotExist()
        pauseHost()
        resumeHost()
        compose.runOnIdle {
            fixture.assertUnavailable(1)
            assertEquals(2, fixture.externalOpens)
        }
    }

    @Test
    fun externalControlUsesCurrentSandboxAndDisablesUnsafeInitialUrl() {
        val fixture = AndroidWebViewHostFixture().apply { constructorFailure = UnsupportedOperationException() }
        show { fixture.Content() }
        compose.runOnUiThread { fixture.navigationAllowed = false }
        compose.waitForIdle()
        compose.onNodeWithText(label(Res.string.webview_open_external_browser)).performClick()
        compose.onNodeWithText(label(Res.string.webview_external_browser_failed)).assertIsDisplayed()
        compose.runOnIdle { assertEquals(0, fixture.externalOpens) }
        compose.runOnUiThread { fixture.url = "javascript:alert(1)" }
        compose.waitForIdle()
        compose.onNodeWithText(label(Res.string.webview_open_external_browser)).assertIsNotEnabled()
        compose.runOnIdle { fixture.assertUnavailable(1) }
    }

    private fun assertDisposedClientsAreInert(fixture: AndroidWebViewHostFixture) {
        compose.runOnIdle {
            fixture.clients
                .single()
                .navigation
                .onPageFinished(fixture.views.single(), WEBVIEW_TEST_URL)
            fixture.clients.single().progress.onProgressChanged(
                fixture.views.single(),
                DISPOSED_CLIENT_PROGRESS_PERCENT,
            )
            assertEquals(1, fixture.pages.size)
            assertReleasedOnce(fixture.views.single())
        }
    }

    private class Request : WebResourceRequest {
        override fun getUrl(): Uri = Uri.parse(WEBVIEW_TEST_URL)

        override fun isForMainFrame(): Boolean = true

        override fun isRedirect(): Boolean = false

        override fun hasGesture(): Boolean = false

        override fun getMethod(): String = "GET"

        override fun getRequestHeaders(): MutableMap<String, String> = mutableMapOf()
    }
}

private const val DISPOSED_CLIENT_PROGRESS_PERCENT = 50
