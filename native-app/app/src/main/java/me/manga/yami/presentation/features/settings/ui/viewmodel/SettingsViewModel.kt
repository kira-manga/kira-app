package me.manga.yamiapk.presentation.features.settings.ui.viewmodel

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import me.manga.yamiapk.R
import me.manga.yamiapk.presentation.features.settings.domain.SettingsRepository
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settingsRepo: SettingsRepository,
    @ApplicationContext private val context: Context
) : AndroidViewModel(context.applicationContext as Application) {

    // existing flows
    val downloadedOnly = settingsRepo.downloadedOnlyFlow
    val incognito    = settingsRepo.incognitoFlow

    val darkMode: StateFlow<Boolean> = settingsRepo.darkModeFlow
        .stateIn(viewModelScope, SharingStarted.Companion.Eagerly, settingsRepo.isDarkMode())

    val pureBlack: StateFlow<Boolean> = settingsRepo.pureBlackFlow
        .stateIn(viewModelScope, SharingStarted.Companion.Eagerly, settingsRepo.isPureBlack())

    val followSystem: StateFlow<Boolean> = settingsRepo.followSystemFlow
        .stateIn(viewModelScope, SharingStarted.Companion.Eagerly, settingsRepo.isFollowSystem())

    // new: cacheSize as StateFlow<String>
    private val _cacheSize = MutableStateFlow(context.getString(R.string.calculating))
    val cacheSize: StateFlow<String> = _cacheSize

    init {
        // calculate initial cache size
        viewModelScope.launch(Dispatchers.IO) { updateCacheSize() }
    }


    // … your other settings–repo functions …
    fun toggleDarkMode(on: Boolean)          = settingsRepo.setDarkMode(on)
    fun togglePureBlack(on: Boolean)         = settingsRepo.setPureBlack(on)
    fun toggleFollowSystem(on: Boolean)         = settingsRepo.setFollowSystem(on)


    fun setDownloadedOnly(enabled: Boolean)  = viewModelScope.launch { settingsRepo.setDownloadedOnly(enabled) }
    fun setIncognito(enabled: Boolean)       = viewModelScope.launch { settingsRepo.setIncognito(enabled) }
    fun setFollowSystem(enabled: Boolean)       = viewModelScope.launch { settingsRepo.setFollowSystem(enabled) }





    // public API to clear all files >1 MB
    fun clearLargeCache() {
        viewModelScope.launch(Dispatchers.IO) {
            settingsRepo.clearFilesLargerThan1MB(context.cacheDir)
            context.externalCacheDir?.let { settingsRepo.clearFilesLargerThan1MB(it) }
            updateCacheSize()
        }
    }

    // helpers
    private fun updateCacheSize() {
        _cacheSize.value = settingsRepo.formatSize(context,settingsRepo.getFolderSize(context.cacheDir))
    }








}