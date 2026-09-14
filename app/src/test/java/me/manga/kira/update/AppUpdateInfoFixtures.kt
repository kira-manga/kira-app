package me.manga.kira.update

import com.google.android.gms.tasks.Tasks
import com.google.android.play.core.appupdate.AppUpdateInfo
import com.google.android.play.core.appupdate.AppUpdateOptions
import com.google.android.play.core.appupdate.testing.FakeAppUpdateManager
import com.google.android.play.core.install.model.AppUpdateType
import com.google.android.play.core.install.model.InstallStatus
import com.google.android.play.core.install.model.UpdateAvailability
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.mockito.Mockito

/** Approved SDK-info gap: the public fake cannot express AVAILABLE with neither type allowed. */
internal fun availableUpdateWithNeitherAllowed(): AppUpdateInfo {
    val info = Mockito.mock(AppUpdateInfo::class.java)
    Mockito.`when`(info.updateAvailability()).thenReturn(UpdateAvailability.UPDATE_AVAILABLE)
    Mockito.`when`(info.isUpdateTypeAllowed(Mockito.anyInt())).thenReturn(false)
    Mockito.`when`(info.isUpdateTypeAllowed(Mockito.any(AppUpdateOptions::class.java))).thenReturn(false)
    return info
}

/** The genuine Play2.1.0 fake drops both launch intents once its download is in progress. */
internal fun assertFakeDownloadingDisallowsBoth(fake: FakeAppUpdateManager) {
    val info = fake.appUpdateInfo.result
    assertEquals(UpdateAvailability.DEVELOPER_TRIGGERED_UPDATE_IN_PROGRESS, info.updateAvailability())
    assertEquals(InstallStatus.DOWNLOADING, info.installStatus())
    for (type in listOf(AppUpdateType.IMMEDIATE, AppUpdateType.FLEXIBLE)) {
        assertFalse(info.isUpdateTypeAllowed(type))
        assertFalse(info.isUpdateTypeAllowed(AppUpdateOptions.newBuilder(type).build()))
    }
}

/** Override only the next query; later ordinary queries still observe the genuine fake's progress. */
internal fun RecordingAppUpdateManager.queueAllowedImmediateRecoveryInfo(): AppUpdateInfo {
    val info = allowedImmediateRecoveryInfo()
    queryOverrides[queries.size + 1] = Tasks.forResult(info)
    return info
}

/** Second approved gap only: a fresh, allowed immediate recovery, not a fake-supported SDK state. */
private fun allowedImmediateRecoveryInfo(): AppUpdateInfo {
    val info = Mockito.mock(AppUpdateInfo::class.java)
    Mockito.`when`(info.updateAvailability()).thenReturn(UpdateAvailability.DEVELOPER_TRIGGERED_UPDATE_IN_PROGRESS)
    Mockito.`when`(info.installStatus()).thenReturn(InstallStatus.DOWNLOADING)
    Mockito.`when`(info.isUpdateTypeAllowed(Mockito.anyInt())).thenAnswer {
        it.getArgument<Int>(0) == AppUpdateType.IMMEDIATE
    }
    Mockito.`when`(info.isUpdateTypeAllowed(Mockito.any(AppUpdateOptions::class.java))).thenAnswer {
        it.getArgument<AppUpdateOptions>(0).appUpdateType() == AppUpdateType.IMMEDIATE
    }
    return info
}
