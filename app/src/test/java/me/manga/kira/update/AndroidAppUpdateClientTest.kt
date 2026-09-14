package me.manga.kira.update

import android.app.Activity
import android.app.Application
import android.os.Looper
import com.google.android.gms.tasks.TaskCompletionSource
import com.google.android.gms.tasks.Tasks
import com.google.android.play.core.appupdate.testing.FakeAppUpdateManager
import com.google.android.play.core.install.model.AppUpdateType
import com.google.android.play.core.install.model.InstallStatus
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import me.manga.kira.platform.update.AndroidAppUpdateClient
import me.manga.kira.platform.update.AppUpdateInfo
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import com.google.android.play.core.appupdate.AppUpdateInfo as PlayAppUpdateInfo

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@LooperMode(LooperMode.Mode.PAUSED)
class AndroidAppUpdateClientTest {
    private val dispatcher = StandardTestDispatcher()
    private val activities = mutableListOf<ActivityController<Activity>>()
    private lateinit var activity: Activity
    private val application: Application get() = RuntimeEnvironment.getApplication()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        activity = newActivity().get()
    }

    @After
    fun tearDown() {
        activities.asReversed().forEach { controller ->
            if (!controller.get().isDestroyed) controller.pause().stop().destroy()
        }
        dispatcher.scheduler.runCurrent()
        Dispatchers.resetMain()
    }

    @Test
    fun selectionAndLaunchAgreeForEverySupportedAvailabilityRow() = runTest(dispatcher) {
        for (case in updateAvailabilityCases(VERSION)) {
            val manager = newManager()
            case.configure(manager)
            assertSelectedUpdateLaunch(case, client(manager), manager, activity, VERSION)
        }
    }

    @Test
    fun startRevalidatesFreshStateAndPreservesSdkFailureAndCancellation() = runTest(dispatcher) {
        val manager = newManager().apply { fake.setUpdateAvailable(VERSION, AppUpdateType.IMMEDIATE) }
        val client = client(manager)
        val selected = client.checkForUpdate()!!
        manager.fake.setUpdateAvailable(VERSION, AppUpdateType.FLEXIBLE)
        assertFalse(client.startUpdate(selected))
        manager.fake.setUpdateNotAvailable()
        assertFalse(client.startUpdate(selected))
        assertTrue(manager.launches.isEmpty())
        assertNotSame(manager.queries[0].result, manager.queries[1].result)

        manager.fake.setUpdateAvailable(VERSION, AppUpdateType.IMMEDIATE)
        manager.launchResult = false
        assertFalse(client.startUpdate(selected))
        manager.launchResult = null
        assertTrue(client.startUpdate(selected))
        assertNotSame(manager.launches[0].info, manager.launches[1].info)
        assertSdkStartFailures(client, manager, selected)
    }

    @Test
    fun launchResolvesTheCurrentUsableHostOnlyAfterTheSdkQuery() = runTest(dispatcher) {
        for (state in listOf("changed", "absent", "finishing", "destroyed", "cancelled")) {
            val manager = newManager().apply { fake.setUpdateAvailable(VERSION, AppUpdateType.FLEXIBLE) }
            var current: Activity? = activity
            val client = AndroidAppUpdateClient(application, { current }, manager)
            val pending = TaskCompletionSource<PlayAppUpdateInfo>()
            manager.queryOverrides[1] = pending.task
            val start = async { client.startUpdate(AppUpdateInfo(VERSION, 0, isImmediate = false)) }
            runCurrent()
            assertTrue(manager.launches.isEmpty())
            current = replacementHost(state, start)
            pending.setResult(manager.fake.appUpdateInfo.result)
            runCurrent()
            if (state == "cancelled") {
                assertTrue(start.isCancelled)
            } else {
                assertEquals(state, state == "changed", start.await())
            }
            if (state == "changed") {
                assertSame(current, manager.launches.single().activity)
            } else {
                assertTrue(state, manager.launches.isEmpty())
            }
        }
    }

    @Test
    fun recoveryResumesImmediateButNeverPromotesAFlexibleDownload() = runTest(dispatcher) {
        for (type in listOf(AppUpdateType.IMMEDIATE, AppUpdateType.FLEXIBLE)) {
            val manager = newManager().apply {
                if (type == AppUpdateType.IMMEDIATE) {
                    fake.setUpdateAvailable(VERSION, type)
                } else {
                    // Both types allowed: a flexible download must not become an immediate flow.
                    fake.setUpdateAvailable(VERSION)
                }
            }
            val client = client(manager)
            assertTrue(client.startUpdate(client.checkForUpdate()!!))
            manager.fake.userAcceptsUpdate()
            manager.fake.downloadStarts()
            assertFakeDownloadingDisallowsBoth(manager.fake)
            if (type == AppUpdateType.IMMEDIATE) {
                assertImmediateRecoveryOutcomes(client, manager)
            } else {
                assertFlexibleDownloadCompletion(client, manager)
            }
        }
    }

    @Test
    fun downloadedListenerIsReplacedAndUnregisteredIdempotently() = runTest(dispatcher) {
        val manager = newManager().apply { fake.setUpdateAvailable(VERSION, AppUpdateType.FLEXIBLE) }
        val client = client(manager)
        var replacedCallbacks = 0
        var callbacks = 0
        client.registerUpdateListener { replacedCallbacks++ }
        client.registerUpdateListener { callbacks++ }
        assertEquals(1, manager.listeners.size)
        assertEquals(1, manager.removedListeners.size)
        assertTrue(client.startUpdate(client.checkForUpdate()!!))
        manager.fake.userAcceptsUpdate()
        manager.fake.downloadStarts()
        manager.fake.downloadCompletes()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(0, replacedCallbacks)
        assertEquals(1, callbacks)
        client.unregisterUpdateListener()
        client.unregisterUpdateListener()
        assertTrue(manager.listeners.isEmpty())
        assertEquals(2, manager.removedListeners.size)
    }

    private suspend fun assertSdkStartFailures(
        client: AndroidAppUpdateClient,
        manager: RecordingAppUpdateManager,
        selected: AppUpdateInfo,
    ) {
        manager.launchFailure = IOException("launch failure")
        assertFalse(client.startUpdate(selected))
        val cancellation = CancellationException("launch cancelled")
        manager.launchFailure = cancellation
        try {
            client.startUpdate(selected)
            fail("CancellationException was swallowed")
        } catch (actual: CancellationException) {
            assertSame(cancellation, actual)
        }
        manager.launchFailure = null
        manager.queryOverrides[manager.queries.size + 1] = Tasks.forException(IOException("query failure"))
        assertFalse(client.startUpdate(selected))
        manager.queryOverrides[manager.queries.size + 1] = Tasks.forCanceled()
        try {
            client.startUpdate(selected)
            fail("Cancelled SDK Task was swallowed")
        } catch (_: CancellationException) {
            // Expected: the caller, not the update client, owns cancellation.
        }
    }

    private fun replacementHost(state: String, start: Deferred<Boolean>): Activity? {
        val replacement = if (state == "absent") null else newActivity()
        when (state) {
            "finishing" -> replacement!!.get().finish()
            "destroyed" -> replacement!!.pause().stop().destroy()
            "cancelled" -> start.cancel()
        }
        return replacement?.get()
    }

    private suspend fun assertImmediateRecoveryOutcomes(
        client: AndroidAppUpdateClient,
        manager: RecordingAppUpdateManager,
    ) {
        val before = manager.launches.size
        assertFalse(client.resumeUpdate()) // Genuine fake progress is disallowed, not resumable.
        assertEquals(before, manager.launches.size)
        var previousInfo = manager.queries.last().result
        for ((index, outcome) in listOf("started", "false", "exception").withIndex()) {
            val info = manager.queueAllowedImmediateRecoveryInfo()
            manager.launchResult = if (outcome == "false") false else null
            manager.launchFailure = if (outcome == "exception") IOException("recovery failure") else null
            assertEquals(outcome == "started", client.resumeUpdate())
            assertEquals(before + index + 1, manager.launches.size)
            val launch = manager.launches.last()
            assertEquals(AppUpdateType.IMMEDIATE, launch.type)
            assertSame(activity, launch.activity)
            assertEquals(100, launch.requestCode)
            assertSame(info, launch.info)
            assertSame(manager.queries.last().result, launch.info)
            assertNotSame(previousInfo, info)
            previousInfo = info
        }
    }

    private suspend fun assertFlexibleDownloadCompletion(
        client: AndroidAppUpdateClient,
        manager: RecordingAppUpdateManager,
    ) {
        val before = manager.launches.size
        assertFalse(client.resumeUpdate())
        assertEquals(before, manager.launches.size)
        manager.fake.downloadCompletes()
        assertEquals(InstallStatus.DOWNLOADED, manager.fake.appUpdateInfo.result.installStatus())
        assertTrue(client.resumeUpdate())
        assertEquals(1, manager.completions)
        assertTrue(manager.fake.isInstallSplashScreenVisible)
        assertEquals(before, manager.launches.size)
    }

    private fun newActivity(): ActivityController<Activity> =
        Robolectric.buildActivity(Activity::class.java).create().start().resume().also(activities::add)

    private fun newManager() = RecordingAppUpdateManager(FakeAppUpdateManager(application))

    private fun client(manager: RecordingAppUpdateManager) =
        AndroidAppUpdateClient(application, { activity }, manager)

    private companion object {
        const val VERSION = 101
    }
}
