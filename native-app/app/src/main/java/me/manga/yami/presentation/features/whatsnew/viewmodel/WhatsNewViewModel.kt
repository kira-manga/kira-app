package me.manga.yamiapk.presentation.features.whatsnew.viewmodel

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import me.manga.yamiapk.core.storage.DataStoreHelper
import me.manga.yamiapk.core.storage.PrefsDelegate
import me.manga.yamiapk.presentation.features.whatsnew.data.WhatsNewRemoteDataSource
import me.manga.yamiapk.presentation.features.whatsnew.data.getDefaultFeatures
import me.manga.yamiapk.presentation.features.whatsnew.model.MediaType
import me.manga.yamiapk.presentation.features.whatsnew.model.WhatsNewFeature
import javax.inject.Inject

@HiltViewModel
class WhatsNewViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val ds: DataStoreHelper,
    private val remoteDataSource: WhatsNewRemoteDataSource
) : ViewModel() {

    companion object {
        private const val TAG = "WhatsNewViewModel"
    }

    // Use PrefsDelegate for storing version and timestamp
    private var lastShownVersion: Int by PrefsDelegate(
        context = context,
        key = "whats_new_last_shown_version",
        defaultValue = 0
    )

    private var lastShownTimestamp: Long by PrefsDelegate(
        context = context,
        key = "whats_new_last_shown_timestamp",
        defaultValue = 0L
    )

    private val _features = MutableStateFlow<List<WhatsNewFeature>>(emptyList())
    val features: StateFlow<List<WhatsNewFeature>> = _features.asStateFlow()

    private val _shouldShowWhatsNew = MutableStateFlow(false)
    val shouldShowWhatsNew: StateFlow<Boolean> = _shouldShowWhatsNew.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _loadError = MutableStateFlow<String?>(null)
    val loadError: StateFlow<String?> = _loadError.asStateFlow()

    private var hasLoadedFeatures = false

    init {
        checkIfShouldShowWhatsNew()
    }

    private fun checkIfShouldShowWhatsNew() {
        viewModelScope.launch(Dispatchers.IO) {
            val currentVersion = getCurrentVersionCode()
            val lastVersion = lastShownVersion

            val shouldShow = currentVersion > lastVersion

            if (shouldShow) {
                ds.setNewSources(true)
                _shouldShowWhatsNew.value = true
                // Only load features if we should show What's New
                loadFeatures()
            } else {
                _shouldShowWhatsNew.value = false
            }
        }
    }

    /**
     * Call this method when the user manually opens the What's New screen
     * or when the screen is actually displayed
     */
    fun ensureFeaturesLoaded() {
        if (!hasLoadedFeatures && _features.value.isEmpty()) {
            loadFeatures()
        }
    }

    private fun loadFeatures() {
        // Prevent multiple simultaneous loads
        if (_isLoading.value) return

        viewModelScope.launch(Dispatchers.IO) {
            try {
                _isLoading.value = true
                _loadError.value = null

                val languageCode = getUserLanguageCode()
                Log.d(TAG, "Loading features for language: $languageCode")

                // Try to fetch from remote
                val result = remoteDataSource.fetchWhatsNewFeatures(languageCode)

                if (result.isSuccess) {
                    val response = result.getOrNull()
                    if (response != null && response.features.isNotEmpty()) {
                        // Convert remote features to local features
                        val localizedFeatures = response.features.mapNotNull { remoteFeature ->
                            try {
                                val localized = remoteDataSource.getLocalizedFeature(
                                    remoteFeature,
                                    languageCode
                                )

                                WhatsNewFeature(
                                    title = localized.title,
                                    description = localized.description,
                                    mediaType = parseMediaType(localized.mediaType),
                                    imageRes = parseImageResource(localized.imageRes),
                                    imageUrlList = localized.imageList,
                                    imageUrl = localized.imageUrl,
                                    videoUrl = localized.videoUrl,
                                    isNew = localized.isNew,
                                    version = localized.version
                                )
                            } catch (e: Exception) {
                                Log.e(TAG, "Error parsing feature", e)
                                null
                            }
                        }

                        if (localizedFeatures.isNotEmpty()) {
                            _features.value = localizedFeatures
                            hasLoadedFeatures = true
                            Log.d(TAG, "Loaded ${localizedFeatures.size} features from remote")
                        } else {
                            // Fall back to default features
                            loadDefaultFeatures()
                        }
                    } else {
                        // Fall back to default features
                        loadDefaultFeatures()
                    }
                } else {
                    // Fall back to default features on error
                    val error = result.exceptionOrNull()
                    Log.e(TAG, "Failed to load remote features", error)
                    _loadError.value = error?.message
                    loadDefaultFeatures()
                }

            } catch (e: Exception) {
                Log.e(TAG, "Error loading features", e)
                _loadError.value = e.message
                loadDefaultFeatures()
            } finally {
                _isLoading.value = false
            }
        }
    }

    private fun loadDefaultFeatures() {
        _features.value = getDefaultFeatures(context)
        hasLoadedFeatures = true
        Log.d(TAG, "Loaded default features")
    }

    private fun getUserLanguageCode(): String {
        return try {
            val locale = context.resources.configuration.locales[0]
            locale.language
        } catch (e: Exception) {
            Log.e(TAG, "Error getting user language", e)
            "en"
        }
    }

    private fun parseMediaType(mediaType: String): MediaType {
        return try {
            MediaType.valueOf(mediaType.uppercase())
        } catch (e: Exception) {
            Log.e(TAG, "Invalid media type: $mediaType", e)
            MediaType.IMAGE
        }
    }

    private fun parseImageResource(resourceName: String?): Int? {
        if (resourceName.isNullOrEmpty()) return null

        return try {
            // If it's a drawable resource name like "ic_feature_1"
            context.resources.getIdentifier(
                resourceName,
                "drawable",
                context.packageName
            ).takeIf { it != 0 }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing image resource: $resourceName", e)
            null
        }
    }

    fun markWhatsNewAsSeen() {
        viewModelScope.launch(Dispatchers.IO) {
            _shouldShowWhatsNew.value = false

            val currentVersion = getCurrentVersionCode()
            lastShownVersion = currentVersion
            lastShownTimestamp = System.currentTimeMillis()
        }
    }

    fun forceShowWhatsNew() {
        _shouldShowWhatsNew.value = true
        // Ensure features are loaded when forced to show
        ensureFeaturesLoaded()
    }

    fun retryLoadFeatures() {
        hasLoadedFeatures = false
        loadFeatures()
    }

    private fun getCurrentVersionCode(): Int {
        return try {
            val packageInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.getPackageInfo(
                    context.packageName,
                    PackageManager.PackageInfoFlags.of(0)
                )
            } else {
                context.packageManager.getPackageInfo(context.packageName, 0)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                packageInfo.longVersionCode.toInt()
            } else {
                @Suppress("DEPRECATION")
                packageInfo.versionCode
            }
        } catch (e: Exception) {
            0
        }
    }

    fun shouldShowBasedOnTime(): Boolean {
        val lastShown = lastShownTimestamp
        val daysSinceLastShown = (System.currentTimeMillis() - lastShown) / (24 * 60 * 60 * 1000)
        return daysSinceLastShown >= 30
    }

    fun resetWhatsNew() {
        lastShownVersion = 0
        lastShownTimestamp = 0L
        _shouldShowWhatsNew.value = true
        hasLoadedFeatures = false
        _features.value = emptyList()
    }
}


































//
//import android.content.Context
//import android.content.SharedPreferences
//import android.content.pm.PackageManager
//import android.os.Build
//import android.util.Log
//import androidx.datastore.preferences.core.edit
//import androidx.datastore.preferences.core.intPreferencesKey
//import androidx.datastore.preferences.core.longPreferencesKey
//import androidx.lifecycle.ViewModel
//import androidx.lifecycle.viewModelScope
//import dagger.hilt.android.lifecycle.HiltViewModel
//import dagger.hilt.android.qualifiers.ApplicationContext
//import kotlinx.coroutines.Dispatchers
//import kotlinx.coroutines.flow.MutableStateFlow
//import kotlinx.coroutines.flow.StateFlow
//import kotlinx.coroutines.flow.asStateFlow
//import kotlinx.coroutines.flow.first
//import kotlinx.coroutines.flow.map
//import kotlinx.coroutines.launch
//import me.manga.yamiapk.core.storage.DataStoreHelper
//import me.manga.yamiapk.core.storage.PrefsDelegate
//import me.manga.yamiapk.presentation.features.whatsnew.data.getDefaultFeatures
//import me.manga.yamiapk.presentation.features.whatsnew.model.WhatsNewFeature
//import javax.inject.Inject
//@HiltViewModel
//class WhatsNewViewModel @Inject constructor(
//    @ApplicationContext private val context: Context,
//    private val ds: DataStoreHelper,
//
//    ) : ViewModel() {
//
//    // Use PrefsDelegate for storing version and timestamp
//    private var lastShownVersion: Int by PrefsDelegate(
//        context = context,
//        key = "whats_new_last_shown_version",
//        defaultValue = 0
//    )
//    private var newSource: Boolean by PrefsDelegate(
//        context = context,
//        key = "new_sources_added",
//        defaultValue = true
//    )
//
//    private var lastShownTimestamp: Long by PrefsDelegate(
//        context = context,
//        key = "whats_new_last_shown_timestamp",
//        defaultValue = 0L
//    )
//
//    private val _features = MutableStateFlow<List<WhatsNewFeature>>(emptyList())
//    val features: StateFlow<List<WhatsNewFeature>> = _features.asStateFlow()
//
//    private val _shouldShowWhatsNew = MutableStateFlow(false)
//    val shouldShowWhatsNew: StateFlow<Boolean> = _shouldShowWhatsNew.asStateFlow()
//
//    private val _isLoading = MutableStateFlow(true)
//    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()
//
//    init {
//        checkIfShouldShowWhatsNew()
//        loadFeatures()
//    }
//
//    private fun checkIfShouldShowWhatsNew() {
//        viewModelScope.launch(Dispatchers.IO) {
//
//            _isLoading.value = true
//
//            val currentVersion = getCurrentVersionCode()
//            val lastVersion = lastShownVersion
//
//             if (currentVersion > lastVersion) ds.setNewSources(true)
//
//            _shouldShowWhatsNew.value = currentVersion > lastVersion
//            _isLoading.value = false
//        }
//    }
//
//    private fun loadFeatures() {
//        viewModelScope.launch {
//            // You can load features from a remote source, local database, or use default ones
//            _features.value = getDefaultFeatures(context)
//        }
//    }
//
//    fun markWhatsNewAsSeen() {
//        viewModelScope.launch(Dispatchers.IO) {
//            // Set state to false immediately
//
//            _shouldShowWhatsNew.value = false
//
//            // Update preferences
//            val currentVersion = getCurrentVersionCode()
//            lastShownVersion = currentVersion
//            lastShownTimestamp = System.currentTimeMillis()
//        }
//    }
//
//    fun forceShowWhatsNew() {
//        _shouldShowWhatsNew.value = true
//    }
//
//    private fun getCurrentVersionCode(): Int {
//        return try {
//            val packageInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
//                context.packageManager.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(0))
//            } else {
//                context.packageManager.getPackageInfo(context.packageName, 0)
//            }
//            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
//                packageInfo.longVersionCode.toInt()
//            } else {
//                @Suppress("DEPRECATION")
//                packageInfo.versionCode
//            }
//        } catch (e: Exception) {
//            0
//        }
//    }
//
//    fun shouldShowBasedOnTime(): Boolean {
//        val lastShown = lastShownTimestamp
//        val daysSinceLastShown = (System.currentTimeMillis() - lastShown) / (24 * 60 * 60 * 1000)
//        return daysSinceLastShown >= 30 // show again after 30 days
//    }
//
//    // Method to reset What's New (useful for testing)
//    fun resetWhatsNew() {
//        lastShownVersion = 0
//        lastShownTimestamp = 0L
//        _shouldShowWhatsNew.value = true
//    }
//}