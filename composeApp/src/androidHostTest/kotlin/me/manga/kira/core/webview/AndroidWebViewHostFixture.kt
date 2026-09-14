package me.manga.kira.core.webview

import android.webkit.CookieManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

internal class AndroidWebViewHostFixture {
    val controller = AndroidWebViewController()
    var url by mutableStateOf(WEBVIEW_TEST_URL)
    var userAgent by mutableStateOf(WEBVIEW_TEST_UA)
    var redraw by mutableIntStateOf(0)
    var visible by mutableStateOf(true)
    var navigationAllowed by mutableStateOf(true)
    var renderedTick = -1
    var constructorFailure: Throwable? = null
    var setupFailure: Throwable? = null
    var callbackFailure: RuntimeException? = null
    var fireDuringSetup = false
    var externalSucceeds = false
    var probes = 0
    var allocations = 0
    var externalOpens = 0
    val views = mutableListOf<RecordingWebView>()
    val clients = mutableListOf<AndroidWebViewClients>()
    val pages = mutableListOf<Pair<Int, String>>()
    val cookies = mutableListOf<String>()
    val userAgents = mutableListOf<String>()

    private val platform =
        AndroidWebViewPlatform(
            probe = {
                probes++
                true
            },
            create = { context ->
                allocations++
                constructorFailure?.let { throw it }
                RecordingWebView(context).also { views += it }
            },
            configure = { view, ua, installed ->
                clients += installed
                configureAndroidWebView(view, ua, installed)
                if (fireDuringSetup) installed.navigation.onPageFinished(view, url)
                setupFailure?.let { throw it }
            },
            openExternal = { _, _ ->
                externalOpens++
                externalSucceeds
            },
        )

    @Suppress("FunctionNaming", "ktlint:standard:function-naming") // Compose UI naming convention.
    @Composable
    fun Content() {
        val tick = redraw
        val allowed = navigationAllowed
        SideEffect { renderedTick = tick }
        if (!visible) return
        CompositionLocalProvider(LocalAndroidWebViewPlatform provides platform) {
            WebViewHost(
                url = url,
                userAgent = userAgent,
                onPageFinished = {
                    callbackFailure?.let { failure -> throw failure }
                    pages += tick to it
                },
                onCookiesAvailable = { cookies += it },
                onUserAgentResolved = { userAgents += it },
                allowNavigation = { _, _ -> allowed },
                controller = controller,
            )
        }
    }

    fun assertUnavailable(attempts: Int) {
        assertEquals(WebViewInitialization.Unavailable, controller.state.value.initialization)
        assertEquals(attempts, probes)
        assertEquals(attempts, allocations)
        assertTrue(pages.isEmpty())
        assertTrue(cookies.isEmpty())
        assertTrue(userAgents.isEmpty())
    }

    fun assertReady(attempts: Int) {
        assertEquals(WebViewInitialization.Ready, controller.state.value.initialization)
        assertEquals(attempts, probes)
        assertEquals(attempts, allocations)
        assertNotNull(views.last().parent)
        assertEquals(listOf(url), views.last().loads)
    }

    fun finishPage() {
        CookieManager.getInstance().setCookie(url, "test_session=synthetic")
        clients.last().navigation.onPageFinished(views.last(), null)
    }

    fun assertLatestCapture() {
        assertEquals(listOf(redraw to url), pages)
        assertEquals(listOf("test_session=synthetic"), cookies)
        assertEquals(listOf(userAgent), userAgents)
        assertEquals(userAgent, views.last().settings.userAgentString)
    }
}
