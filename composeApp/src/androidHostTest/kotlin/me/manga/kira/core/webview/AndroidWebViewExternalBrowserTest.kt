package me.manga.kira.core.webview

import android.content.ActivityNotFoundException
import android.content.ContextWrapper
import android.content.Intent
import kotlinx.coroutines.CancellationException
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AndroidWebViewExternalBrowserTest {
    @Test
    fun unsafeInitialUrlsAndChangedSandboxCannotLaunch() {
        val context = RecordingLaunchContext()
        val unsafe =
            listOf(
                "",
                " ",
                "about:blank",
                "file:///tmp/test",
                "content://local/test",
                "javascript:alert(1)",
                "data:text/plain,test",
                "https:///missing-host",
                "https://source.example/%ZZ",
                "https://a:b@source.example",
            )
        unsafe.forEach { url ->
            assertFalse(
                openWebViewExternally(context, emptyWebViewCallbacks().copy(url = url), ::launchWebViewExternalBrowser),
            )
        }
        val rejected = emptyWebViewCallbacks().copy(allowNavigation = { _, _ -> false })
        assertFalse(openWebViewExternally(context, rejected, ::launchWebViewExternalBrowser))
        assertTrue(context.intents.isEmpty())
        assertTrue(openWebViewExternally(context, emptyWebViewCallbacks(), ::launchWebViewExternalBrowser))
        assertSafeIntent(context.intents.single())
    }

    @Test
    fun missingHandlerAndSecurityDenialReturnFailureWithoutAuthExtras() {
        val context = RecordingLaunchContext()
        listOf(ActivityNotFoundException(), SecurityException()).forEach { failure ->
            context.failure = failure
            assertFalse(launchWebViewExternalBrowser(context, WEBVIEW_TEST_URL))
        }
        assertEquals(2, context.intents.size)
        context.intents.forEach(::assertSafeIntent)
    }

    @Test
    fun unexpectedAndWrappedCriticalLaunchFailuresEscape() {
        val context = RecordingLaunchContext()
        val unexpected = IllegalArgumentException("application error")
        context.failure = unexpected
        assertSame(
            unexpected,
            assertFailsWith<IllegalArgumentException> {
                launchWebViewExternalBrowser(context, WEBVIEW_TEST_URL)
            },
        )
        val cancellation = CancellationException("cancel launch")
        context.failure = SecurityException("denied", cancellation)
        assertSame(
            cancellation,
            assertFailsWith<CancellationException> {
                launchWebViewExternalBrowser(context, WEBVIEW_TEST_URL)
            },
        )
    }

    private fun assertSafeIntent(intent: Intent) {
        assertEquals(Intent.ACTION_VIEW, intent.action)
        assertEquals(WEBVIEW_TEST_URL, intent.dataString)
        assertEquals(Intent.FLAG_ACTIVITY_NEW_TASK, intent.flags)
        assertNull(intent.extras)
    }

    private class RecordingLaunchContext : ContextWrapper(webViewTestContext()) {
        val intents = mutableListOf<Intent>()
        var failure: RuntimeException? = null

        override fun startActivity(intent: Intent) {
            intents += intent
            failure?.let { throw it }
        }
    }
}
