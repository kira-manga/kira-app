package me.manga.yamiapk.presentation.features.settings.domain

import android.content.Context
import android.content.res.Configuration
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import me.manga.yamiapk.R
import me.manga.yamiapk.core.storage.DataStoreHelper
import me.manga.yamiapk.core.storage.SharedPrefsHelper
import me.manga.yamiapk.core.storage.StorageKeys
import me.manga.yamiapk.presentation.features.settings.data.ONE_MB
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton


@Singleton
class SettingsRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val prefsHelper: SharedPrefsHelper,
    private val ds: DataStoreHelper,
) {
    val downloadedOnlyFlow = ds.downloadedOnlyFlow
    val incognitoFlow = ds.incognitoFlow
    val readingModeFlow = ds.readingModeFlow
    val hasShownRemoveBookMarkFlow = ds.hasShownRemoveBookMark
    val languageFlow: Flow<String> = ds.languageFlow


    suspend fun setLanguage(code: String) = ds.setLanguage(code)
    suspend fun setDownloadedOnly(v: Boolean) = ds.setDownloadedOnly(v)
    suspend fun setIncognito(v: Boolean) = ds.setIncognito(v)
    suspend fun setReadingMode(m: String) = ds.setReadingMode(m)
    suspend fun setShownRemoveBookMark(v: Boolean) = ds.setShownRemoveBookMark(v)

    fun setDarkMode(enabled: Boolean) {
        prefsHelper.putBoolean(StorageKeys.KEY_THEME_MODE, enabled)
    }

    fun setPureBlack(enabled: Boolean) {
        prefsHelper.putBoolean(StorageKeys.KEY_PURE_BLACK, enabled)
    }
    fun setFollowSystem(enabled: Boolean) {
        prefsHelper.putBoolean(StorageKeys.KEY_THEME_SYSTEM, enabled)
    }
    fun isDarkMode(): Boolean {
        return if (prefsHelper.sharedPreferences.contains(StorageKeys.KEY_THEME_MODE)) {
            prefsHelper.getBoolean(StorageKeys.KEY_THEME_MODE, false)
        } else {
            val uiMode = context.resources.configuration.uiMode and
                    Configuration.UI_MODE_NIGHT_MASK
            uiMode == Configuration.UI_MODE_NIGHT_YES
        }
    }

    fun isPureBlack(): Boolean =
        prefsHelper.getBoolean(StorageKeys.KEY_PURE_BLACK, defaultValue = true)


    fun isFollowSystem(): Boolean =
        prefsHelper.getBoolean(StorageKeys.KEY_THEME_SYSTEM, defaultValue = true)

    // Provide Flow<Boolean> for dark mode
    val darkModeFlow: Flow<Boolean> = prefsHelper.booleanPrefFlow(
        key = StorageKeys.KEY_THEME_MODE,
        default = isDarkMode()
    )

     val followSystemFlow: Flow<Boolean> =
        prefsHelper.booleanPrefFlow(
            key    = StorageKeys.KEY_THEME_SYSTEM,
            default = true // or true, whatever makes sense for first‑run
        )

    val pureBlackFlow: Flow<Boolean> = prefsHelper.booleanPrefFlow(
        key = StorageKeys.KEY_PURE_BLACK,
        default = isPureBlack()
    )


//    private val systemDarkFlow = context.systemDarkModeFlow()

    fun clearFilesLargerThan1MB(dir: File) {
        if (!dir.exists()) return

        dir.walkTopDown()
            .filter { it.isFile && it.length() > ONE_MB }
            .forEach { it.delete() }

        // clean up any now-empty directories
        dir.walkBottomUp()
            .filter { it.isDirectory && it.listFiles()?.isEmpty() == true }
            .forEach { it.delete() }
    }

    fun getFolderSize(dir: File): Long =
        dir.listFiles()?.sumOf { if (it.isDirectory) getFolderSize(it) else it.length() } ?: 0L





//    private fun Context.systemDarkModeFlow() = callbackFlow {
//        // Helper to map a Configuration to a Boolean
//        fun Configuration.isSystemDark() =
//            (uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
//
//        // Emit the initial value
//        trySend(resources.configuration.isSystemDark())
//
//        // Register to listen for configuration changes
//        val callbacks = object : ComponentCallbacks {
//            override fun onConfigurationChanged(newConfig: Configuration) {
//                trySend(newConfig.isSystemDark())
//            }
//            override fun onLowMemory() = Unit
//        }
//        registerComponentCallbacks(callbacks)
//
//        // Clean up when collector is cancelled
//        awaitClose { unregisterComponentCallbacks(callbacks) }
//    }.distinctUntilChanged()




    fun formatSize(context: Context, size: Long): String {
        val kb = 1024L
        val mb = kb * 1024
        val gb = mb * 1024
        return when {
            size >= gb -> context.getString(R.string.gigabytes, size.toDouble() / gb)
            size >= mb -> context.getString(R.string.megabytes, size.toDouble() / mb)
            size >= kb -> context.getString(R.string.kilobytes, size.toDouble() / kb)
            else -> "${size} ${context.getString(R.string.bytes)}"
        }
    }
}