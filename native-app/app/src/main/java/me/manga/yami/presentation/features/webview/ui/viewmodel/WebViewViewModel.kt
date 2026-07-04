package me.manga.yamiapk.presentation.features.webview.ui.viewmodel

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.manga.yamiapk.core.storage.DataStoreHelper
import me.manga.yamiapk.data.local.dao.SourcesDao
import me.manga.yamiapk.di.sources.provider.ActiveRepoProvider
import me.manga.yamiapk.presentation.features.repo_settings.domain.SourcesRepository
import javax.inject.Inject

@HiltViewModel
class WebViewViewModel @Inject constructor(
    private val dataStoreManager: DataStoreHelper,
    private val activeRepoProvider: ActiveRepoProvider,
    private val sourcesRepository: SourcesRepository,


): ViewModel() {
    fun saveHeaders(headers: Map<String, String>?, api: String) {
        if (headers.isNullOrEmpty()) {
            return
        }

        viewModelScope.launch(Dispatchers.IO) {
            try {
                val repo = sourcesRepository.getRepoByName(api)
                    repo.refreshHeaders(headers)

            } catch (e: Exception) {

            }
        }
    }
}