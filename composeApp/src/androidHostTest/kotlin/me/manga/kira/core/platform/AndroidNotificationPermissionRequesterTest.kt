package me.manga.kira.core.platform

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.result.contract.ActivityResultContracts
import me.manga.kira.core.webview.AndroidWebViewComposeTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.util.ReflectionHelpers
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@LooperMode(LooperMode.Mode.PAUSED)
class AndroidNotificationPermissionRequesterTest : AndroidWebViewComposeTest() {
    @Test
    fun actualRequesterStaysOptionalBeforeDuringAndAfterFalseResults() {
        val fixture = openRequester()
        val results = mutableListOf<Boolean>()
        compose.runOnIdle {
            assertOptional(fixture, granted = false)
            assertTrue(fixture.launches.isEmpty())
        }

        // Repeated denial and cancellation-shaped results are supplied at the registry boundary.
        // These are not a simulation of fixed/managed denial or the system dialog's dismissal UI.
        listOf(Activity.RESULT_OK, Activity.RESULT_OK, Activity.RESULT_CANCELED).forEachIndexed { index, resultCode ->
            compose.runOnIdle {
                fixture.requester.request { results += it }
                assertOptional(fixture, granted = false)
                assertEquals(index, results.size)
                val launch = fixture.launches.last()
                assertEquals(ActivityResultContracts.RequestPermission::class.java, launch.contractType)
                assertEquals(Manifest.permission.POST_NOTIFICATIONS, launch.input)
            }
            compose.waitForIdle()
            compose.runOnIdle {
                assertEquals(index + 1, fixture.launches.size)
                assertTrue(fixture.complete(granted = false, resultCode = resultCode))
                assertEquals(List(index + 1) { false }, results)
                assertOptional(fixture, granted = false)
            }
        }
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(results.size, fixture.launches.size) }
    }

    @Test
    fun explicitGrantResultUpdatesTheFlowBeforeCallingTheRequesterCallback() {
        val fixture = openRequester()
        val observed = mutableListOf<Pair<Boolean, Boolean>>()
        compose.runOnIdle {
            fixture.requester.request { granted ->
                observed += granted to fixture.requester.hasPermission.value
            }
            assertFalse(fixture.requester.hasPermission.value)
            assertTrue(observed.isEmpty())
            assertTrue(fixture.complete(granted = true))
            assertEquals(listOf(true to true), observed)
            assertOptional(fixture, granted = true)
            assertEquals(1, fixture.launches.size)
        }
    }

    @Test
    fun resumeReprobesBothGrantAndRevocationWithoutLaunchingARequest() {
        val fixture = openRequester()
        val original = fixture.requester
        pauseHost()
        compose.runOnUiThread {
            shadowOf(activity.application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        }
        resumeHost()
        compose.runOnIdle {
            assertSame(original, fixture.requester)
            assertOptional(fixture, granted = true)
            assertTrue(fixture.launches.isEmpty())
        }

        pauseHost()
        compose.runOnUiThread {
            shadowOf(activity.application).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        }
        resumeHost()
        compose.runOnIdle {
            assertOptional(fixture, granted = false)
            assertTrue(fixture.launches.isEmpty())
        }
    }

    @Test
    fun compositionDisposalRemovesTheOldResultCallbackAndResumeObserver() {
        val fixture = openRequester()
        val disposed = fixture.requester
        val results = mutableListOf<Boolean>()
        compose.runOnIdle {
            disposed.request { results += it }
            fixture.visible = false
        }
        compose.waitForIdle()
        compose.runOnIdle {
            fixture.complete(granted = true)
            assertTrue(results.isEmpty())
            assertFalse(disposed.hasPermission.value)
        }

        pauseHost()
        compose.runOnUiThread {
            shadowOf(activity.application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        }
        resumeHost()
        compose.runOnIdle { assertFalse(disposed.hasPermission.value) }
        compose.runOnUiThread {
            shadowOf(activity.application).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
            fixture.visible = true
        }
        compose.waitForIdle()
        compose.runOnIdle {
            assertNotSame(disposed, fixture.requester)
            assertOptional(fixture, granted = false)
            assertTrue(results.isEmpty())
            assertEquals(1, fixture.launches.size)
        }
    }

    @Test
    fun aReplacementHostGetsAFreshOptionalRequesterWithoutTheOldPendingCallback() {
        val first = openRequester()
        val results = mutableListOf<Boolean>()
        compose.runOnIdle { first.requester.request { results += it } }
        // Reuse the existing owned host lifecycle, not the real App root or a copied first-launch gate.
        destroyHost()
        createHost()
        val replacement = openRequester()
        compose.runOnIdle {
            first.complete(granted = true)
            assertTrue(results.isEmpty())
            assertNotSame(first.requester, replacement.requester)
            assertOptional(replacement, granted = false)
            assertTrue(replacement.launches.isEmpty())
        }
    }

    @Test
    fun pre33BranchReportsImplicitGrantWithoutLaunchingARuntimeRequest() {
        val originalSdk = Build.VERSION.SDK_INT
        try {
            // Exercise only the production SDK branch in the installed SDK35 host; no SDK32/device claim.
            ReflectionHelpers.setStaticField(Build.VERSION::class.java, "SDK_INT", Build.VERSION_CODES.S_V2)
            val fixture = openRequester()
            val results = mutableListOf<Boolean>()
            compose.runOnIdle {
                assertOptional(fixture, granted = true)
                fixture.requester.request { results += it }
                assertEquals(listOf(true), results)
                assertTrue(fixture.launches.isEmpty())
            }
        } finally {
            ReflectionHelpers.setStaticField(Build.VERSION::class.java, "SDK_INT", originalSdk)
        }
    }

    @Test
    fun settingsActionStillTargetsThisApplicationWithoutGrantingPermission() {
        val fixture = openRequester()
        compose.runOnIdle {
            fixture.requester.openAppSettings()
            val intent = assertNotNull(shadowOf(activity.application).nextStartedActivity)
            assertEquals(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, intent.action)
            assertEquals(Uri.fromParts("package", activity.packageName, null), intent.data)
            assertTrue(intent.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
            assertOptional(fixture, granted = false)
            assertTrue(fixture.launches.isEmpty())
        }
    }

    private fun openRequester(): NotificationPermissionRequesterFixture {
        compose.runOnUiThread {
            shadowOf(activity.application).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        }
        val fixture = NotificationPermissionRequesterFixture()
        show { fixture.Content() }
        return fixture
    }

    private fun assertOptional(
        fixture: NotificationPermissionRequesterFixture,
        granted: Boolean,
    ) {
        val requester = fixture.requester
        assertEquals(NotificationPermissionOnboardingPolicy.OPTIONAL_USER_INITIATED, requester.onboardingPolicy)
        assertFalse(requester.onboardingPolicy.requestAutomatically)
        assertFalse(requester.onboardingPolicy.requireGrantToContinue)
        assertEquals(granted, requester.hasPermission.value)
    }
}
