@file:OptIn(co.touchlab.kermit.ExperimentalKermitApi::class)

package me.manga.kira.locale

import android.content.Context
import androidx.work.Configuration
import androidx.work.DelegatingWorkerFactory
import co.touchlab.kermit.Logger
import co.touchlab.kermit.MessageStringFormatter
import co.touchlab.kermit.Severity
import co.touchlab.kermit.crashlytics.CrashlyticsLogWriter
import com.google.android.play.core.install.InstallStateUpdatedListener
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import java.util.concurrent.ExecutorService
import me.manga.kira.MyApp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements

/** SDK-only boundaries; real Application attachment, Koin, resources and notification builders run. */
internal object AppLocaleStartupGuards {
    private var armed = false
    private var workManager: RecordOnlyWorkManager? = null
    private val unexpectedCalls = mutableListOf<String>()
    val updateListeners = mutableSetOf<InstallStateUpdatedListener>()
    var firebaseInitializations = 0
    var crashWriterConstructions = 0
    var updateManagerCreations = 0
    var updateInfoRequests = 0

    fun arm() {
        check(!armed && workManager == null && unexpectedCalls.isEmpty())
        armed = true
    }

    fun requireArmed(call: String) {
        if (!armed) unexpectedCall("Before test setup: $call")
    }

    fun initializeWork(context: Context, configuration: Configuration) {
        requireArmed("WorkManager.initialize")
        if (workManager != null) unexpectedCall("Repeated WorkManager.initialize")
        check(context.applicationContext is MyApp)
        workManager = RecordOnlyWorkManager(configuration)
    }

    fun isWorkInitialized(): Boolean {
        requireArmed("WorkManager.isInitialized")
        return workManager != null
    }

    fun currentWorkManager(): RecordOnlyWorkManager {
        requireArmed("WorkManager.getInstance")
        return workManager ?: unexpectedCall("WorkManager.getInstance before initialize")
    }

    fun assertExpectedStartup() {
        assertEquals("Caught SDK errors must still fail the test", emptyList<String>(), unexpectedCalls)
        val manager = checkNotNull(workManager) { "Real Koin must initialize the guarded WorkManager" }
        assertTrue(manager.configuration.workerFactory is DelegatingWorkerFactory)
        assertEquals("Record the actual startup request, never execute it", 1, manager.periodicRequests.size)
        assertTrue("Guard explicit and any provider Firebase initialization", firebaseInitializations >= 1)
        assertEquals("Guard the actual optional writer construction", 1, crashWriterConstructions)
        assertTrue(Logger.config.logWriterList.any { it is CrashlyticsLogWriter })
        assertEquals("Real AppUpdateClient must reach the guarded SDK factory", 1, updateManagerCreations)
        assertTrue("Do not advance the Activity's queued update flow", updateInfoRequests in 0..1)
        assertTrue("Activity destruction must release its update listener", updateListeners.isEmpty())
    }

    fun clear() {
        val configuration = workManager?.configuration
        workManager = null
        updateListeners.clear()
        unexpectedCalls.clear()
        firebaseInitializations = 0
        crashWriterConstructions = 0
        updateManagerCreations = 0
        updateInfoRequests = 0
        armed = false
        // Koin's Configuration.Builder owns these default executors; no work was submitted to them.
        configuration?.let {
            setOf(it.executor, it.taskExecutor)
                .filterIsInstance<ExecutorService>().forEach { executor -> executor.shutdown() }
        }
    }

    fun unexpectedCall(call: String): Nothing {
        unexpectedCalls += call
        throw AssertionError("Unexpected SDK operation in resource-only test: $call")
    }
}

/** No Firebase app/components/executors are initialized, including through FirebaseInitProvider. */
@Implements(FirebaseApp::class, isInAndroidSdk = false)
class ResourceTestFirebaseShadow {
    companion object {
        @JvmStatic
        @Implementation
        fun initializeApp(context: Context): FirebaseApp? {
            AppLocaleStartupGuards.requireArmed("FirebaseApp.initializeApp")
            check(context.applicationContext is MyApp)
            AppLocaleStartupGuards.firebaseInitializations += 1
            return null
        }

        @JvmStatic
        @Implementation
        @Suppress("UNUSED_PARAMETER")
        fun initializeApp(context: Context, options: FirebaseOptions): FirebaseApp =
            AppLocaleStartupGuards.unexpectedCall("FirebaseApp.initializeApp(options)")

        @JvmStatic
        @Implementation
        @Suppress("UNUSED_PARAMETER")
        fun initializeApp(context: Context, options: FirebaseOptions, name: String): FirebaseApp =
            AppLocaleStartupGuards.unexpectedCall("FirebaseApp.initializeApp(named)")

        @JvmStatic
        @Implementation
        fun getInstance(): FirebaseApp = AppLocaleStartupGuards.unexpectedCall("FirebaseApp.getInstance")

        @JvmStatic
        @Implementation
        @Suppress("UNUSED_PARAMETER")
        fun getInstance(name: String): FirebaseApp =
            AppLocaleStartupGuards.unexpectedCall("FirebaseApp.getInstance(named)")
    }
}

/** Intercepts Kermit2.1.0's primary constructor before CrashKiOS/backend initialization. */
@Implements(CrashlyticsLogWriter::class, isInAndroidSdk = false)
@Suppress("UNUSED_PARAMETER")
class ResourceTestCrashlyticsWriterShadow {
    @Implementation(methodName = "__constructor__")
    fun construct(minSeverity: Severity, minCrashSeverity: Severity?, formatter: MessageStringFormatter) {
        AppLocaleStartupGuards.requireArmed("CrashlyticsLogWriter constructor")
        AppLocaleStartupGuards.crashWriterConstructions += 1
    }

    @Implementation
    fun isLoggable(tag: String, severity: Severity): Boolean = false

    @Implementation
    fun log(severity: Severity, message: String, tag: String, throwable: Throwable?) {
        AppLocaleStartupGuards.requireArmed("CrashlyticsLogWriter.log")
    }
}
