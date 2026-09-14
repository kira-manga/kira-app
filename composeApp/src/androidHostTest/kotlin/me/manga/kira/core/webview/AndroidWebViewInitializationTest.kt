package me.manga.kira.core.webview

import android.util.AndroidRuntimeException
import android.widget.FrameLayout
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
class AndroidWebViewInitializationTest {
    @Test
    fun citedConstructorFailuresAreUnavailableButUnexpectedRuntimeEscapes() {
        val accepted =
            listOf(
                UnsupportedOperationException(),
                AndroidRuntimeException(),
                IllegalStateException("provider initialization state"),
            )
        accepted.forEach { failure ->
            val platform = AndroidWebViewPlatform(probe = { true }, create = { throw failure })
            assertNull(initializeAndroidWebView(webViewTestContext(), platform, null, emptyWebViewClients()))
        }
        val unexpected = IllegalArgumentException("unexpected constructor failure")
        val platform = AndroidWebViewPlatform(probe = { true }, create = { throw unexpected })
        assertSame(
            unexpected,
            assertFailsWith<IllegalArgumentException> {
                initializeAndroidWebView(webViewTestContext(), platform, null, emptyWebViewClients())
            },
        )
    }

    @Test
    fun directAndWrappedCancellationOomAndLinkageEscapeAfterPartialCleanup() {
        val critical = listOf(CancellationException("cancel"), OutOfMemoryError("synthetic"), LinkageError("synthetic"))
        critical.forEach { cause ->
            assertPartialFailure(cause, cause)
            assertPartialFailure(AndroidRuntimeException("provider", cause), cause)
        }
        val initializer = ExceptionInInitializerError(AssertionError("synthetic"))
        assertPartialFailure(AndroidRuntimeException("provider", initializer), initializer)
    }

    @Test
    fun cleanupPreservesOriginalCriticalAndPropagatesCleanupCritical() {
        val cancellation = CancellationException("original cancellation")
        val oom = OutOfMemoryError("original fatal")
        assertCleanupFailure(
            AndroidRuntimeException(cancellation),
            IllegalStateException("cleanup state"),
            cancellation,
        )
        assertCleanupFailure(oom, CancellationException("cleanup cancellation"), oom)
        val cleanupCancellation = CancellationException("cleanup cancellation")
        assertCleanupFailure(
            UnsupportedOperationException(),
            AndroidRuntimeException(cleanupCancellation),
            cleanupCancellation,
        )
        val cleanupOom = OutOfMemoryError("cleanup fatal")
        assertCleanupFailure(AndroidRuntimeException(), cleanupOom, cleanupOom)
    }

    @Test
    fun destroyFailureCannotReplaceCancellationRaisedByCleanup() {
        val cancellation = CancellationException("stop loading cancelled")
        val view =
            RecordingWebView(webViewTestContext()).apply {
                stopFailure = cancellation
                destroyFailure = OutOfMemoryError("destroy fatal")
            }
        val platform =
            AndroidWebViewPlatform(
                probe = { true },
                create = { view },
                configure = { _, _, _ -> throw UnsupportedOperationException() },
            )
        assertSame(
            cancellation,
            assertFailsWith<CancellationException> {
                initializeAndroidWebView(webViewTestContext(), platform, null, emptyWebViewClients())
            },
        )
        assertReleasedOnce(view)
    }

    @Test
    fun causeCyclesTerminateAndDoNotHideCriticalCauses() {
        val first = AndroidRuntimeException()
        val second = IllegalStateException("cycle member")
        first.initCause(second)
        second.initCause(first)
        assertEquals(2, webViewCauses(first).count())
        assertNull(webViewCriticalCause(first))
        val critical = CancellationException("cycle cancellation")
        val wrapper = AndroidRuntimeException(critical)
        critical.initCause(wrapper)
        assertSame(critical, webViewCriticalCause(wrapper))
    }

    @Test
    fun staleAttemptCannotPublishOrDetachNewOwnerAndReleaseIsOnceOnCreationThread() {
        val controller = AndroidWebViewController()
        assertEquals(WebViewInitialization.Initializing, controller.state.value.initialization)
        val stale = AndroidWebViewAttempt(controller).apply { begin() }
        val live = AndroidWebViewAttempt(controller).apply { begin() }
        val staleView = RecordingWebView(webViewTestContext())
        stale.publish(OwnedAndroidWebView(staleView))
        assertReleasedOnce(staleView)
        assertEquals(WebViewInitialization.Initializing, controller.state.value.initialization)
        val liveView = RecordingWebView(webViewTestContext())
        FrameLayout(webViewTestContext()).addView(liveView)
        live.publish(OwnedAndroidWebView(liveView))
        stale.dispose()
        assertEquals(WebViewInitialization.Ready, controller.state.value.initialization)
        assertFalse(stale.accepts(liveView))
        assertTrue(live.accepts(liveView))
        live.dispose()
        live.dispose()
        controller.reload()
        assertReleasedOnce(liveView)
    }

    private fun assertPartialFailure(
        failure: Throwable,
        expected: Throwable,
    ) {
        val view = RecordingWebView(webViewTestContext())
        val platform =
            AndroidWebViewPlatform(
                probe = { true },
                create = { view },
                configure = { _, _, _ -> throw failure },
            )
        assertSame(
            expected,
            assertFailsWith<Throwable> {
                initializeAndroidWebView(webViewTestContext(), platform, null, emptyWebViewClients())
            },
        )
        assertReleasedOnce(view)
    }

    private fun assertCleanupFailure(
        failure: Throwable,
        cleanup: Throwable,
        expected: Throwable,
    ) {
        val view = RecordingWebView(webViewTestContext()).apply { stopFailure = cleanup }
        val platform =
            AndroidWebViewPlatform(
                probe = { true },
                create = { view },
                configure = { _, _, _ -> throw failure },
            )
        assertSame(
            expected,
            assertFailsWith<Throwable> {
                initializeAndroidWebView(webViewTestContext(), platform, null, emptyWebViewClients())
            },
        )
        assertReleasedOnce(view)
    }
}
