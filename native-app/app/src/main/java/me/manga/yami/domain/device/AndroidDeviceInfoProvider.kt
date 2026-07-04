package me.manga.yamiapk.domain.device

import android.content.Context
import android.os.Build
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AndroidDeviceInfoProvider @Inject constructor(
    @ApplicationContext private val context: Context
) : DeviceInfoProvider {
    override fun getDeviceMetadata(): Map<String, Any> {
        val pkgInfo = context.packageManager
            .getPackageInfo(context.packageName, 0)


        return mapOf(
            "manufacturer" to Build.MANUFACTURER,
            "model"        to Build.MODEL,
            "osVersion"    to Build.VERSION.SDK_INT,
            "appVersion"   to pkgInfo.versionName.orEmpty(),
            "packageName"  to context.packageName,
        )
    }
}