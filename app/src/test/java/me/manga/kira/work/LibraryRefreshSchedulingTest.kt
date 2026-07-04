package me.manga.kira.work

import androidx.work.NetworkType
import kotlin.time.Duration.Companion.hours
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the M2 periodic-refresh contract (2026-07-03): the Android background library refresh must
 * stay in lockstep with the iOS `BGAppRefreshTask` request (12h cadence, one-interval initial
 * delay), drive the existing [LibraryRefreshWorker], require connectivity + non-low battery, and
 * never share a unique-work name with the manual pull-to-refresh chain (which uses REPLACE and
 * would otherwise cancel/replace the periodic schedule).
 */
class LibraryRefreshSchedulingTest {

    @Test
    fun periodicRequest_runsTheLibraryRefreshWorker_every12Hours() {
        val spec = LibraryRefreshScheduling.periodicRequest().workSpec

        assertEquals(LibraryRefreshWorker::class.java.name, spec.workerClassName)
        assertTrue("must be periodic, not one-time", spec.isPeriodic)
        assertEquals(12.hours.inWholeMilliseconds, spec.intervalDuration)
    }

    @Test
    fun periodicRequest_firstRunIsDeferredOneInterval_mirroringIosEarliestBeginDate() {
        val spec = LibraryRefreshScheduling.periodicRequest().workSpec

        assertEquals(12.hours.inWholeMilliseconds, spec.initialDelay)
    }

    @Test
    fun periodicRequest_requiresNetworkAndHealthyBattery() {
        val constraints = LibraryRefreshScheduling.periodicRequest().workSpec.constraints

        assertEquals(NetworkType.CONNECTED, constraints.requiredNetworkType)
        assertTrue(constraints.requiresBatteryNotLow())
    }

    @Test
    fun periodicUniqueName_neverCollidesWithTheManualRefreshChain() {
        // "LibraryRefresh" is LibraryRefreshRepositoryImpl.REFRESH_WORK_NAME (private const) — the
        // manual pull-to-refresh one-time unique chain enqueued with ExistingWorkPolicy.REPLACE.
        assertNotEquals("LibraryRefresh", LibraryRefreshScheduling.PERIODIC_WORK_NAME)
    }
}
