package me.manga.kira.core.webview

import android.content.Context
import org.robolectric.RuntimeEnvironment
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

internal const val WEBVIEW_TEST_URL = "https://source.example/challenge"
internal const val WEBVIEW_TEST_UA = "Kira-WebView-test-agent"

internal fun webViewTestContext(): Context = RuntimeEnvironment.getApplication()

internal fun emptyWebViewClients(): AndroidWebViewClients =
    AndroidWebViewClients(
        attempt = AndroidWebViewAttempt(null),
        current = { emptyWebViewCallbacks() },
    )

internal fun emptyWebViewCallbacks(): AndroidWebViewCallbacks =
    AndroidWebViewCallbacks(
        url = WEBVIEW_TEST_URL,
        onPageFinished = {},
        onCookiesAvailable = {},
        onUserAgentResolved = {},
        allowNavigation = null,
    )

internal fun assertReleasedOnce(view: RecordingWebView) {
    assertEquals(1, view.stopCalls)
    assertEquals(1, view.destroyCalls)
    assertTrue(view.detachedAtDestroy)
    assertSame(view.creationThread, view.destroyThread)
}
