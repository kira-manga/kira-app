package me.manga.kira.di

import android.app.Application
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.Uri
import com.google.firebase.FirebaseApp
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import me.manga.kira.BuildConfig
import me.manga.kira.domain.model.complaint.ComplaintType
import me.manga.kira.domain.repository.FeedbackRepository
import me.manga.kira.platform.analytics.AnalyticsClient
import me.manga.kira.platform.crash.CrashReporter
import me.manga.kira.platform.firebase.DisabledAnalyticsClient
import me.manga.kira.platform.firebase.DisabledCrashReporter
import me.manga.kira.platform.firebase.DisabledPushTokenProvider
import me.manga.kira.platform.firebase.DisabledRemoteDocStore
import me.manga.kira.platform.firebase.FirebaseServicesUnavailableException
import me.manga.kira.platform.firebase.firebaseServicesAvailable
import me.manga.kira.platform.push.PushTokenProvider
import me.manga.kira.platform.remote.RemoteDocStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.android.ext.koin.androidContext
import org.koin.dsl.koinApplication
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class DebugServiceIsolationTest {
    @Test
    fun effectiveDebugIdentityAndProviderAuthoritiesAreNotTheStoreIdentity() {
        val context: Application = RuntimeEnvironment.getApplication()
        assertTrue(BuildConfig.DEBUG)
        assertEquals("me.manga.kira.debug", BuildConfig.APPLICATION_ID)
        assertEquals(BuildConfig.APPLICATION_ID, context.packageName)
        assertEquals("Kira Manga Debug", context.applicationInfo.loadLabel(context.packageManager).toString())
        assertNotNull(context.packageManager.resolveContentProvider("me.manga.kira.debug.fileprovider", 0))
        assertNull(context.packageManager.resolveContentProvider("me.manga.kira.fileprovider", 0))
        val projectId = context.resources.getIdentifier("project_id", "string", context.packageName)
        assertTrue("Debug must select the dedicated inert Firebase client", projectId != 0)
        assertEquals("kira-debug-disabled", context.getString(projectId))
    }

    @Test
    fun mergedDebugManifestCannotInitializeFirebaseOrInterceptProductionActivationLinks() {
        val context: Application = RuntimeEnvironment.getApplication()
        @Suppress("DEPRECATION")
        val info = context.packageManager.getApplicationInfo(context.packageName, PackageManager.GET_META_DATA)
        assertTrue(info.metaData.getBoolean("firebase_analytics_collection_deactivated"))
        for (key in listOf(
            "firebase_data_collection_default_enabled",
            "firebase_crashlytics_collection_enabled",
            "firebase_messaging_auto_init_enabled",
            "firebase_inapp_messaging_auto_data_collection_enabled",
        )) {
            assertTrue("Missing explicit Debug service policy: $key", info.metaData.containsKey(key))
            assertFalse(info.metaData.getBoolean(key, true))
        }
        assertNull(context.packageManager.resolveContentProvider("${context.packageName}.firebaseinitprovider", 0))
        for (url in listOf("https://kiramanga.me/activate", "kiramanga://activate")) {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                .addCategory(Intent.CATEGORY_BROWSABLE).setPackage(context.packageName)
            assertTrue(context.packageManager.queryIntentActivities(intent, 0).isEmpty())
        }
        assertTrue(FirebaseApp.getApps(context).isEmpty())
    }

    @Test
    fun lazyDebugGraphResolvesWithoutFirebaseAndFeedbackCannotReportSuccess() = runTest {
        val context: Application = RuntimeEnvironment.getApplication()
        assertTrue(FirebaseApp.getApps(context).isEmpty())
        val app = koinApplication {
            androidContext(context)
            modules(allSharedModules() + platformModule() + allReworkModules())
        }
        try {
            assertSame(DisabledAnalyticsClient, app.koin.get<AnalyticsClient>())
            assertSame(DisabledCrashReporter, app.koin.get<CrashReporter>())
            assertSame(DisabledPushTokenProvider, app.koin.get<PushTokenProvider>())
            assertSame(DisabledRemoteDocStore, app.koin.get<RemoteDocStore>())
            assertNull(app.koin.get<PushTokenProvider>().getToken())
            assertNull(app.koin.get<PushTokenProvider>().observeTokens().first())
            val result = app.koin.get<FeedbackRepository>().submit(ComplaintType.TECHNICAL, "Debug isolation", "No remote write")
            assertTrue(result.isFailure)
            assertTrue(result.exceptionOrNull() is FirebaseServicesUnavailableException)
            assertTrue(FirebaseApp.getApps(context).isEmpty())
        } finally {
            app.close()
        }
    }

    @Test
    fun productionServicesRequireBothCanonicalIdentityAndANonDebuggableInstallation() {
        val context: Application = RuntimeEnvironment.getApplication()
        fun available(id: String, debuggable: Boolean): Boolean =
            firebaseServicesAvailable(object : ContextWrapper(context) {
                override fun getPackageName(): String = id
                override fun getApplicationInfo(): ApplicationInfo = ApplicationInfo().apply {
                    flags = if (debuggable) ApplicationInfo.FLAG_DEBUGGABLE else 0
                }
            })
        assertTrue(available("me.manga.kira", false))
        assertFalse(available("me.manga.kira", true))
        assertFalse(available("me.manga.kira.debug", false))
        assertFalse(available("me.manga.kira.debug", true))
        assertFalse(available("me.manga.kira.other", false))
    }
}
