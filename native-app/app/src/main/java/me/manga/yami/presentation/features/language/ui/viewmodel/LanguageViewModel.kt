package me.manga.yamiapk.presentation.features.language.ui.viewmodel

import android.content.Context
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import me.manga.yamiapk.presentation.features.settings.domain.SettingsRepository
import javax.inject.Inject

@HiltViewModel
class LanguageViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settingsRepo: SettingsRepository,

    ) : ViewModel() {


    val selectedLanguageFlow: Flow<String?> = settingsRepo.languageFlow

    fun selectLanguage(code: String) {
        viewModelScope.launch {
            settingsRepo.setLanguage(code)
            updateLocale( code)
        }
    }

    private fun updateLocale( languageCode: String) {
        AppCompatDelegate.setApplicationLocales(
            LocaleListCompat.forLanguageTags(languageCode)
        )
        // Note: You may need to restart activity or recreate UI to apply changes
    }

}