package me.manga.yamiapk.presentation.features.refresh.ui.viewmodel

import android.app.Application
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.asFlow
import androidx.lifecycle.map
import androidx.lifecycle.viewModelScope
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import me.manga.yamiapk.work.LibraryRefreshWorker
import javax.inject.Inject

@HiltViewModel
class RefreshViewModel @Inject constructor(
    private val application: Application,
    private val workManager: WorkManager

) : ViewModel() {

    companion object {
        private const val REFRESH_WORK_NAME = "LibraryRefresh"


    }

    private val downloadWorkInfos =
        workManager.getWorkInfosForUniqueWorkLiveData(REFRESH_WORK_NAME)


    val isScheduled: Flow<Boolean> = downloadWorkInfos
        .map { infos ->
            infos.any { it.state == WorkInfo.State.ENQUEUED }
        }
        .asFlow()

    val isWorkRunning: Flow<Boolean> = downloadWorkInfos
        .map { infos ->
            infos.any { it.state == WorkInfo.State.RUNNING }
        }
        .asFlow()


    init {



        workManager
            .getWorkInfosForUniqueWorkLiveData(REFRESH_WORK_NAME)
            .asFlow()
            .map { infos ->
                // You’ll typically only have one WorkInfo for the unique chain:
                infos.firstOrNull()
            }
            .filterNotNull()
            .onEach { info ->
                val data = info.progress



                when (info.state) {
                    WorkInfo.State.SUCCEEDED -> {
                    }

                    WorkInfo.State.FAILED -> {
                    }

                    else -> {
                        // still ENQUEUED or RUNNING – do nothing here
                    }
                }
            }.flowOn(Dispatchers.IO)
            .launchIn(viewModelScope)
    }


     fun refreshLibrary(){
        viewModelScope.launch {


            val request = OneTimeWorkRequestBuilder<LibraryRefreshWorker>()
                .build()

            workManager.enqueueUniqueWork(
                REFRESH_WORK_NAME,
                ExistingWorkPolicy.REPLACE,
                request
            )

            }
        }


}