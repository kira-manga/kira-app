package me.manga.kira.core.webview

import android.content.pm.PackageInfo
import android.os.RemoteException
import kotlinx.coroutines.CancellationException
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AndroidWebViewCapabilityTest {
    @Test
    fun missingContextAndFeatureSkipProviderQueries() {
        var features = 0
        var packages = 0
        val feature = { _: android.content.Context ->
            features++
            false
        }
        val provider = {
            packages++
            PackageInfo()
        }
        assertFalse(probeWebViewProvider(null, feature, provider))
        assertEquals(0, features)
        assertFalse(probeWebViewProvider(webViewTestContext(), feature, provider))
        assertEquals(1, features)
        assertEquals(0, packages)
    }

    @Suppress("TooGenericExceptionThrown") // Exercise the platform's exact IPC wrapper.
    @Test
    fun packageAndIpcOutcomesAreFreshHints() {
        var packageInfo: PackageInfo? = null
        val context = webViewTestContext()
        assertFalse(probeWebViewProvider(context, { true }, { packageInfo }))
        packageInfo = PackageInfo()
        assertTrue(probeWebViewProvider(context, { true }, { packageInfo }))
        packageInfo = null
        assertFalse(probeWebViewProvider(context, { true }, { packageInfo }))
        assertFalse(probeWebViewProvider(context, { true }, { throw RuntimeException(RemoteException()) }))
    }

    @Test
    fun probeDoesNotSwallowUnexpectedOrWrappedCriticalFailures() {
        val unexpected = IllegalArgumentException("not an IPC failure")
        assertSame(unexpected, assertFailsWith<IllegalArgumentException> { probeThrowing(unexpected) })
        val cancellation = CancellationException("cancel probe")
        val wrapped = RuntimeException(RemoteException().apply { initCause(cancellation) })
        assertSame(cancellation, assertFailsWith<CancellationException> { probeThrowing(wrapped) })
        val oom = OutOfMemoryError("synthetic")
        assertSame(oom, assertFailsWith<OutOfMemoryError> { probeThrowing(RuntimeException(oom)) })
    }

    private fun probeThrowing(failure: RuntimeException): Boolean =
        probeWebViewProvider(
            context = webViewTestContext(),
            hasFeature = { true },
            currentProvider = { throw failure },
        )
}
