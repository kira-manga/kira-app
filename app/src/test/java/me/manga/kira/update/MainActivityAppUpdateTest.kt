package me.manga.kira.update

import com.google.android.gms.tasks.TaskCompletionSource
import com.google.android.play.core.appupdate.AppUpdateOptions
import com.google.android.play.core.install.model.AppUpdateType
import com.google.android.play.core.install.model.UpdateAvailability
import java.io.IOException
import me.manga.kira.MainActivity
import me.manga.kira.platform.activity.ActivityHolder
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import com.google.android.play.core.appupdate.AppUpdateInfo as PlayAppUpdateInfo

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = AppUpdateTestApplication::class)
@LooperMode(LooperMode.Mode.PAUSED)
class MainActivityAppUpdateTest {
    private val fixture = MainActivityUpdateFixture()
    private val manager get() = fixture.manager
    private val review get() = fixture.review

    @Before
    fun setUp() = fixture.setUp()

    @After
    fun tearDown() = fixture.tearDown()

    @Test
    fun foregroundOrdersRecoveryThenSelectedStartAndConsumesTrueOrFalseAttempts() {
        for (succeeds in listOf(true, false)) {
            manager.fake.setUpdateAvailable(VERSION, AppUpdateType.IMMEDIATE)
            manager.launchResult = if (succeeds) null else false
            val beforeQueries = manager.queries.size
            val beforeLaunches = manager.launches.size
            val beforeEvents = manager.events.size
            val beforeReviews = review.requests.get()
            val activity = fixture.createActivity()
            fixture.drainMain()
            assertNull(ActivityHolder.current)
            assertEquals(beforeQueries, manager.queries.size)
            assertEquals(beforeLaunches, manager.launches.size)
            assertEquals(1, manager.listeners.size)
            fixture.resumeActivity()
            assertSame(activity, ActivityHolder.current)
            assertEquals(listOf("query", "query", "query", "launch"), manager.events.drop(beforeEvents))
            assertEquals(beforeLaunches + 1, manager.launches.size)
            val launch = manager.launches.last()
            assertSame(activity, launch.activity)
            assertSame(manager.queries.last().result, launch.info)
            assertEquals(AppUpdateType.IMMEDIATE, launch.type)
            assertEquals(100, launch.requestCode)
            assertEquals(succeeds, manager.fake.isImmediateFlowVisible)
            assertNoRepeatedConsent(succeeds, beforeLaunches, beforeReviews)
            fixture.destroyActivity()
            assertTrue(manager.listeners.isEmpty())
        }
    }

    @Test
    fun pausedAndOldCancelledQueriesCannotLaunchOrConsumeANewerAttempt() {
        manager.fake.setUpdateAvailable(VERSION, AppUpdateType.IMMEDIATE)
        val firstStart = TaskCompletionSource<PlayAppUpdateInfo>()
        manager.queryOverrides[3] = firstStart.task
        fixture.createActivity()
        fixture.resumeActivity()
        assertEquals(3, manager.queries.size)
        assertTrue(manager.launches.isEmpty())
        // Completion while paused cannot use a stale foreground Activity or consume the guard.
        fixture.pauseActivity()
        firstStart.setResult(manager.fake.appUpdateInfo.result)
        fixture.drainMain()
        assertNull(ActivityHolder.current)
        assertTrue(manager.launches.isEmpty())
        assertLateOldStartPreservesNewAttempt()
        assertDestroyCancelsRecovery()
        review.assertNoRequest()
        assertEquals(1, review.requests.get())
    }

    @Test
    fun pendingImmediateRecoveryNeverCompetesWithAFreshConsentFlow() {
        for (outcome in listOf("false", "exception", "started")) {
            val activity = fixture.createActivity()
            seedPendingImmediateUpdate(activity)
            manager.launchResult = if (outcome == "false") false else null
            manager.launchFailure = if (outcome == "exception") IOException("recovery failed") else null
            val beforeQueries = manager.queries.size
            val beforeLaunches = manager.launches.size
            val info = manager.queueAllowedImmediateRecoveryInfo()
            fixture.resumeActivity()
            assertRecoveryOutcome(outcome, beforeQueries, beforeLaunches, info, activity)
            fixture.destroyActivity()
            manager.fake.userCancelsDownload()
            manager.launchFailure = null
        }
    }

    @Test
    fun flexibleDownloadCompletesThroughTheHostListenerEvenWhilePaused() {
        manager.fake.setUpdateAvailable(VERSION, AppUpdateType.FLEXIBLE)
        fixture.createActivity()
        fixture.resumeActivity()
        assertEquals(AppUpdateType.FLEXIBLE, manager.launches.single().type)
        manager.fake.userAcceptsUpdate()
        manager.fake.downloadStarts()
        fixture.pauseActivity()
        assertNull(ActivityHolder.current)
        assertEquals(1, manager.listeners.size)
        manager.fake.downloadCompletes()
        fixture.drainMain()
        assertEquals(1, manager.completions)
        assertTrue(manager.fake.isInstallSplashScreenVisible)
        assertEquals(1, manager.launches.size)
        fixture.destroyActivity()
        assertTrue(manager.listeners.isEmpty())
    }

    private fun assertNoRepeatedConsent(succeeds: Boolean, beforeLaunches: Int, beforeReviews: Int) {
        fixture.pauseActivity()
        if (succeeds) manager.fake.userRejectsUpdate()
        val completedQueries = manager.queries.size
        fixture.resumeActivity()
        assertEquals(completedQueries + 1, manager.queries.size) // recovery only, no fresh prompt
        assertEquals(beforeLaunches + 1, manager.launches.size)
        review.assertNoRequest()
        assertEquals(beforeReviews + 1, review.requests.get())
    }

    private fun assertLateOldStartPreservesNewAttempt() {
        val secondStart = TaskCompletionSource<PlayAppUpdateInfo>()
        manager.queryOverrides[6] = secondStart.task
        fixture.resumeActivity()
        assertEquals(6, manager.queries.size)
        fixture.pauseActivity()
        // An old result during newer suspended recovery cannot clear its job or consume its guard.
        val newerRecovery = TaskCompletionSource<PlayAppUpdateInfo>()
        manager.queryOverrides[7] = newerRecovery.task
        fixture.resumeActivity()
        secondStart.setResult(manager.fake.appUpdateInfo.result)
        fixture.drainMain()
        assertEquals(7, manager.queries.size)
        assertTrue(manager.launches.isEmpty())
        newerRecovery.setResult(manager.fake.appUpdateInfo.result)
        fixture.drainMain()
        assertEquals(9, manager.queries.size)
        assertEquals(1, manager.launches.size)
        assertSame(manager.queries.last().result, manager.launches.single().info)
    }

    private fun assertDestroyCancelsRecovery() {
        fixture.pauseActivity()
        manager.fake.userRejectsUpdate()
        val destroyedRecovery = TaskCompletionSource<PlayAppUpdateInfo>()
        manager.queryOverrides[10] = destroyedRecovery.task
        fixture.resumeActivity()
        fixture.destroyActivity()
        destroyedRecovery.setResult(manager.fake.appUpdateInfo.result)
        fixture.drainMain()
        assertEquals(10, manager.queries.size)
        assertEquals(1, manager.launches.size)
        assertTrue(manager.listeners.isEmpty())
        assertEquals(1, manager.removedListeners.size)
        assertNull(ActivityHolder.current)
    }

    private fun seedPendingImmediateUpdate(activity: MainActivity) {
        manager.fake.setUpdateAvailable(VERSION, AppUpdateType.IMMEDIATE)
        // Supported transitions establish progress; the allowed recovery info is a separate fixture.
        assertTrue(
            manager.fake.startUpdateFlowForResult(
                manager.fake.appUpdateInfo.result,
                activity,
                AppUpdateOptions.newBuilder(AppUpdateType.IMMEDIATE).build(),
                100,
            ),
        )
        manager.fake.userAcceptsUpdate()
        manager.fake.downloadStarts()
        assertFakeDownloadingDisallowsBoth(manager.fake)
    }

    private fun assertRecoveryOutcome(
        outcome: String,
        beforeQueries: Int,
        beforeLaunches: Int,
        info: PlayAppUpdateInfo,
        activity: MainActivity,
    ) {
        assertEquals(outcome, beforeLaunches + 1, manager.launches.size)
        val launch = manager.launches.last()
        assertEquals(AppUpdateType.IMMEDIATE, launch.type)
        assertSame(activity, launch.activity)
        assertEquals(100, launch.requestCode)
        assertSame(info, launch.info)
        assertSame(info, manager.queries[beforeQueries].result)
        assertNotSame(manager.queries.getOrNull(beforeQueries - 1)?.result, info)
        assertEquals(beforeQueries + if (outcome == "started") 1 else 2, manager.queries.size)
        // A failed recovery's fresh ordinary check must still reject IN_PROGRESS, without a launch.
        assertTrue(
            manager.queries.drop(beforeQueries).all {
                it.result.updateAvailability() == UpdateAvailability.DEVELOPER_TRIGGERED_UPDATE_IN_PROGRESS
            },
        )
        if (outcome != "started") assertNotSame(info, manager.queries.last().result)
    }

    private companion object {
        const val VERSION = 101
    }
}
