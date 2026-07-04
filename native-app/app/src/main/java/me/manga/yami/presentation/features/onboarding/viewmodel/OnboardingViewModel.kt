package me.manga.yamiapk.presentation.features.onboarding.viewmodel

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import me.manga.yamiapk.presentation.features.settings.domain.SettingsRepository
import javax.inject.Inject

@HiltViewModel
class OnboardingViewModel  @Inject constructor(
    private val settingsRepo: SettingsRepository,
    @ApplicationContext private val context: Context
) : ViewModel() {

    val darkMode: StateFlow<Boolean> = settingsRepo.darkModeFlow
        .stateIn(viewModelScope, SharingStarted.Companion.Eagerly, settingsRepo.isDarkMode())

    val followSystem: StateFlow<Boolean> = settingsRepo.followSystemFlow
        .stateIn(viewModelScope, SharingStarted.Companion.Eagerly,
            settingsRepo.isFollowSystem()    // ← now this is a Boolean
        )

    fun toggleDarkMode(on: Boolean)          = settingsRepo.setDarkMode(on)
    fun toggleFollowSystem(on: Boolean)         = settingsRepo.setFollowSystem(on)



}