package me.manga.kira.presentation.features.webview.ui.screens

import kotlinx.coroutines.flow.MutableStateFlow
import me.manga.kira.core.webview.WebViewController
import me.manga.kira.core.webview.WebViewInitialization
import me.manga.kira.core.webview.WebViewNavState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

class WebViewScreenCaptureTest {
    @Test
    fun cookieFirstCaptureRemainsStableAndEnrichesWithUserAgent() {
        val capture = WebViewScreenCapture("https://source.example")
        val cookieCallback = capture.onCookiesAvailable
        assertNull(capture.headers)
        cookieCallback("test_session=synthetic")
        assertEquals(mapOf("Cookie" to "test_session=synthetic"), capture.headers)
        capture.onUserAgentResolved("test-agent")
        assertEquals(mapOf("Cookie" to "test_session=synthetic", "User-Agent" to "test-agent"), capture.headers)
        assertSame(cookieCallback, capture.onCookiesAvailable)
        capture.onCookiesAvailable(" ")
        assertEquals("test_session=synthetic", capture.headers?.get("Cookie"))
    }

    @Test
    fun nonReadyBackClosesWithoutSavingWhileHealthyDefaultUsesHistory() {
        val controller = TestController()
        var saves = 0
        var closes = 0
        val actions = WebViewScreenActions(controller, { saves++ }, { closes++ })
        listOf(WebViewInitialization.Initializing, WebViewInitialization.Unavailable).forEach {
            controller.state.value = WebViewNavState(canGoBack = true, initialization = it)
            actions.back()
            actions.save()
        }
        assertEquals(2, closes)
        assertEquals(0, saves)
        assertEquals(0, controller.backs)
        controller.state.value = WebViewNavState(canGoBack = true)
        actions.back()
        actions.save()
        assertEquals(1, controller.backs)
        assertEquals(1, saves)
        assertEquals(2, closes)
    }

    private class TestController : WebViewController {
        override val state = MutableStateFlow(WebViewNavState())
        var backs = 0

        override fun goBack() {
            backs++
        }

        override fun goForward() = Unit

        override fun reload() = Unit
    }
}
