package me.manga.yamiapk.presentation.features.statistics.domain

import android.content.Context
import android.util.Log
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import me.manga.yamiapk.R
import me.manga.yamiapk.core.storage.DataStoreHelper
import me.manga.yamiapk.data.local.dao.StatisticsDeo
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class StatisticsRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    statisticsDeo: StatisticsDeo,
    private val dataStoreHelper: DataStoreHelper,


    ) {

    val inLibraryFlow: Flow<Int> = statisticsDeo.getTotalMangaCount().flowOn(Dispatchers.IO)
    val chaptersTotalFlow: Flow<Int> = statisticsDeo.getTotalChaptersCount().flowOn(Dispatchers.IO)
    val chaptersDownloadedFlow: Flow<Int> = statisticsDeo.getDownloadedChaptersCount().flowOn(
        Dispatchers.IO)
    val chaptersReadFlow: Flow<Int> = statisticsDeo.getReadChaptersCount().flowOn(Dispatchers.IO)
    val chaptersBookmarkedFlow: Flow<Int> = statisticsDeo.getBookmarkedChaptersCount().flowOn(
        Dispatchers.IO)
    val completedEntriesFlow: Flow<Int> = statisticsDeo.getCompletedMangaCount().flowOn(Dispatchers.IO)
    val startedEntriesFlow: Flow<Int> = statisticsDeo.getStartedMangaCount().flowOn(Dispatchers.IO)

    private val READ_MINUTES = intPreferencesKey("read_minutes")

    val readMinutesFlow: Flow<Int> = dataStoreHelper.dataStore
        .data
        .map { prefs -> prefs[READ_MINUTES] ?: 0 }

    val readDurationFlow: Flow<String> = readMinutesFlow.map { totalMins ->
        val h = totalMins / 60
        val m = totalMins % 60
        context.getString(R.string.h_m, h, m)
    }
    private var sessionStartMillis: Long = 0L

    fun startReadingSession() {
        sessionStartMillis = System.currentTimeMillis()
    }

    /** Call from ViewModel.onPause() (or Fragment.onPause()) */
    suspend fun endReadingSession() {
        val start = sessionStartMillis
        if (start == 0L) return  // no session in progress

        val elapsedMs = System.currentTimeMillis() - start
        sessionStartMillis = 0L

        // Round down to whole minutes
        val addedMinutes = TimeUnit.MILLISECONDS.toMinutes(elapsedMs).toInt()
        if (addedMinutes <= 0) return

        // Persist new total
        dataStoreHelper.dataStore.edit { prefs ->
            val current = prefs[READ_MINUTES] ?: 0

            prefs[READ_MINUTES] = current + addedMinutes
        }
    }






}