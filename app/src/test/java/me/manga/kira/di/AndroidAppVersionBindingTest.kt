package me.manga.kira.di

import android.app.Application
import me.manga.kira.platform.version.AndroidAppVersionProvider
import me.manga.kira.platform.version.AppVersionProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.android.ext.koin.androidContext
import org.koin.dsl.koinApplication
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class AndroidAppVersionBindingTest {
    @Test
    fun versionBindingReadsTheInstalledReleaseName() {
        assertVersionBinding(installedVersion = "7.8.9", expectedVersion = "7.8.9")
    }

    @Test
    fun versionBindingUsesUnknownWhenPackageVersionIsMissing() {
        assertVersionBinding(installedVersion = null, expectedVersion = "unknown")
    }

    private fun assertVersionBinding(installedVersion: String?, expectedVersion: String) {
        val context: Application = RuntimeEnvironment.getApplication()
        @Suppress("DEPRECATION")
        val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
        packageInfo.versionName = installedVersion
        shadowOf(context.packageManager).installPackage(packageInfo)
        val app = koinApplication {
            androidContext(context)
            modules(platformModule())
        }
        try {
            val version = app.koin.get<AppVersionProvider>()
            assertTrue(version is AndroidAppVersionProvider)
            assertEquals(expectedVersion, version.versionName)
            assertEquals(context.packageName, version.packageName)
        } finally {
            app.close()
        }
    }
}
