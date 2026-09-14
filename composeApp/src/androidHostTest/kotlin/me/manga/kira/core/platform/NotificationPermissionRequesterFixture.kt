package me.manga.kira.core.platform

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.app.ActivityOptionsCompat

// Controls the OS-result boundary, not the production requester or Android's PermissionController UI.
internal class NotificationPermissionRequesterFixture : ActivityResultRegistryOwner {
    val launches = mutableListOf<PermissionLaunch>()
    var visible by mutableStateOf(true)
    lateinit var requester: NotificationPermissionRequester
        private set

    override val activityResultRegistry =
        object : ActivityResultRegistry() {
            override fun <I, O> onLaunch(
                requestCode: Int,
                contract: ActivityResultContract<I, O>,
                input: I,
                options: ActivityOptionsCompat?,
            ) {
                launches += PermissionLaunch(requestCode, contract.javaClass, input)
            }
        }

    @Suppress("FunctionNaming", "ktlint:standard:function-naming") // Compose UI naming convention.
    @Composable
    fun Content() {
        if (visible) {
            CompositionLocalProvider(LocalActivityResultRegistryOwner provides this) {
                val current = rememberNotificationPermissionRequester()
                SideEffect { requester = current }
            }
        }
    }

    fun complete(
        granted: Boolean,
        resultCode: Int = Activity.RESULT_OK,
    ): Boolean {
        val data =
            if (resultCode == Activity.RESULT_OK) {
                val grantResult = if (granted) PackageManager.PERMISSION_GRANTED else PackageManager.PERMISSION_DENIED
                Intent().apply {
                    putExtra(
                        ActivityResultContracts.RequestMultiplePermissions.EXTRA_PERMISSIONS,
                        arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                    )
                    putExtra(
                        ActivityResultContracts.RequestMultiplePermissions.EXTRA_PERMISSION_GRANT_RESULTS,
                        intArrayOf(grantResult),
                    )
                }
            } else {
                null
            }
        return activityResultRegistry.dispatchResult(launches.last().requestCode, resultCode, data)
    }

    data class PermissionLaunch(
        val requestCode: Int,
        val contractType: Class<*>,
        val input: Any?,
    )
}
