package me.manga.kira.update

import android.app.Activity
import com.google.android.gms.tasks.Tasks
import com.google.android.play.core.install.model.AppUpdateType
import me.manga.kira.platform.update.AndroidAppUpdateClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue

internal data class UpdateAvailabilityCase(
    val name: String,
    val type: Int?,
    val configure: (RecordingAppUpdateManager) -> Unit,
)

internal fun updateAvailabilityCases(version: Int): List<UpdateAvailabilityCase> = listOf(
    UpdateAvailabilityCase("immediate only", AppUpdateType.IMMEDIATE) {
        it.fake.setUpdateAvailable(version, AppUpdateType.IMMEDIATE)
    },
    UpdateAvailabilityCase("flexible only", AppUpdateType.FLEXIBLE) {
        it.fake.setUpdateAvailable(version, AppUpdateType.FLEXIBLE)
    },
    UpdateAvailabilityCase("both", AppUpdateType.FLEXIBLE) { it.fake.setUpdateAvailable(version) },
    UpdateAvailabilityCase("neither", null) {
        it.queryOverrides[1] = Tasks.forResult(availableUpdateWithNeitherAllowed())
    },
    UpdateAvailabilityCase("not available", null) { it.fake.setUpdateNotAvailable() },
)

internal suspend fun assertSelectedUpdateLaunch(
    case: UpdateAvailabilityCase,
    client: AndroidAppUpdateClient,
    manager: RecordingAppUpdateManager,
    activity: Activity,
    version: Int,
) {
    val selected = client.checkForUpdate()
    if (case.type == null) {
        assertNull(case.name, selected)
        assertTrue(case.name, manager.launches.isEmpty())
        return
    }
    assertNotNull(case.name, selected)
    assertEquals(case.name, case.type == AppUpdateType.IMMEDIATE, selected!!.isImmediate)
    assertEquals(version, selected.availableVersionCode)
    assertTrue(case.name, client.startUpdate(selected))
    val launch = manager.launches.single()
    assertEquals(case.name, case.type, launch.type)
    assertEquals(100, launch.requestCode)
    assertSame(activity, launch.activity)
    assertNotSame(manager.queries[0].result, manager.queries[1].result)
    assertSame(manager.queries[1].result, launch.info)
    assertEquals(case.type == AppUpdateType.IMMEDIATE, manager.fake.isImmediateFlowVisible)
    assertEquals(case.type == AppUpdateType.FLEXIBLE, manager.fake.isConfirmationDialogVisible)
}
