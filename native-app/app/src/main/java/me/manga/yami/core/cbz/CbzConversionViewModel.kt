package me.manga.yamiapk.core.cbz

import android.content.Context
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import me.manga.yamiapk.R

import me.manga.yamiapk.core.storage.DataStoreHelper
import me.manga.yamiapk.data.local.dao.ChapterDao
import me.manga.yamiapk.data.local.dao.MangaDao
import javax.inject.Inject

data class ConversionProgress(
    val isConverting: Boolean = false,
    val totalChapters: Int = 0,
    val convertedChapters: Int = 0,
    val currentMangaTitle: String = "",
    val currentChapterNumber: String = "",
    val error: String? = null,
    val successMessage: String? = null,
    val wasStopped: Boolean = false
)

@HiltViewModel
class CbzConversionViewModel @Inject constructor(
    private val chapterDao: ChapterDao,
    private val mangaDao: MangaDao,
    private val cbzManager: CbzManager,
    private val dataStoreHelper: DataStoreHelper,
    @ApplicationContext private val context: Context,

    ) : ViewModel() {

    private val _conversionProgress = MutableStateFlow(ConversionProgress())
    val conversionProgress: StateFlow<ConversionProgress> = _conversionProgress.asStateFlow()

    private var conversionJob: Job? = null
    private var shouldStopConversion = false

    // Expose DataStore flows
    val useCbzFormatFlow = dataStoreHelper.useCbzFormatFlow
    val autoConvertToCbzFlow = dataStoreHelper.autoConvertToCbzFlow

    /**
     * Toggle CBZ format setting
     */
    fun setUseCbzFormat(enabled: Boolean) {
        viewModelScope.launch {
            dataStoreHelper.setUseCbzFormat(enabled)
        }
    }

    /**
     * Toggle auto-conversion setting
     */
    fun setAutoConvertToCbz(enabled: Boolean) {
        viewModelScope.launch {
            dataStoreHelper.setAutoConvertToCbz(enabled)
        }
    }

    /**
     * Stop the ongoing conversion
     */
    fun stopConversion() {
        shouldStopConversion = true
        conversionJob?.cancel()

        val current = _conversionProgress.value
        _conversionProgress.value = ConversionProgress(
            isConverting = false,
            totalChapters = current.totalChapters,
            convertedChapters = current.convertedChapters,
            wasStopped = true,
            successMessage = buildString {
                append(context.getString(R.string.conversion_stopped_by_user))
                append(
                    context.getString(
                        R.string.chapters_converted_successfully,
                        current.convertedChapters
                    ))
                append(
                    context.getString(
                        R.string.chapters_remaining,
                        current.totalChapters - current.convertedChapters
                    ))
            }
        )
    }

    /**
     * Start converting all existing downloads to CBZ format
     */
    fun startConversion() {
        if (conversionJob?.isActive == true) return

        shouldStopConversion = false

        conversionJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                _conversionProgress.value = ConversionProgress(isConverting = true)

                // Get all downloaded chapters that are NOT already CBZ
                val allDownloadedChapters = chapterDao.getAllDownloadedChapters()

                val chaptersToConvert = allDownloadedChapters.filter { chapter ->
                    chapter.localImagePaths.isNotEmpty() &&
                            !(chapter.localImagePaths.size == 1 &&
                                    chapter.localImagePaths.first().endsWith(".cbz"))
                }

                val total = chaptersToConvert.size
                _conversionProgress.value = _conversionProgress.value.copy(totalChapters = total)

                if (total == 0) {
                    _conversionProgress.value = ConversionProgress(
                        isConverting = false,
                        successMessage = context.getString(R.string.no_chapters_to_convert_all_chapters_are_already_in_cbz_format)
                    )
                    return@launch
                }

                var converted = 0
                var failed = 0

                for ((index, chapter) in chaptersToConvert.withIndex()) {
                    // Check if conversion should stop
                    if (shouldStopConversion) {
                        stopConversion()
                        return@launch
                    }

                    try {
                        // Get manga title for progress display
                        val manga = mangaDao.getMangaById(chapter.mangaId)
                        val mangaTitle = manga?.title ?: "Unknown"

                        _conversionProgress.value = _conversionProgress.value.copy(
                            convertedChapters = index,
                            currentMangaTitle = mangaTitle,
                            currentChapterNumber = chapter.number
                        )

                        // Convert to CBZ
                        val cbzPath = cbzManager.convertFilesToCbz(
                            chapter.mangaId,
                            chapter.id,
                            chapter.localImagePaths
                        )

                        if (cbzPath != null) {
                            // Update database with new CBZ path
                            chapterDao.updateChapterLocalPaths(chapter.id, listOf(cbzPath))
                            converted++

                            // Delete original image files to save space
                            deleteOriginalImages(chapter.localImagePaths, cbzPath)
                        } else {
                            failed++
                        }
                    } catch (e: Exception) {
                        if (shouldStopConversion) {
                            return@launch
                        }
                        failed++
                        Log.e("CbzConversion", "Failed to convert chapter ${chapter.id}", e)
                    }
                }

                // Conversion complete
                val message = buildString {
                    append(context.getString(R.string.conversion_complete)+"\n")
                    append( append(
                        context.getString(
                            R.string.chapters_converted_successfully,
                            converted
                        )))
                    if (failed > 0) {
                        append(context.getString(R.string.chapters_failed, failed))
                    }
                }

                _conversionProgress.value = ConversionProgress(
                    isConverting = false,
                    totalChapters = total,
                    convertedChapters = converted,
                    successMessage = message
                )

            } catch (e: Exception) {
                if (!shouldStopConversion) {
                    _conversionProgress.value = ConversionProgress(
                        isConverting = false,
                        error = context.getString(R.string.conversion_failed)
                    )
                }
            }
        }
    }

    /**
     * Delete original image files after successful CBZ conversion
     */
    private fun deleteOriginalImages(imagePaths: List<String>, cbzPath: String) {
        imagePaths.forEach { path ->
            if (path != cbzPath) {
                try {
                    java.io.File(path).delete()
                } catch (e: Exception) {
                    android.util.Log.w("CbzConversion", "Failed to delete $path", e)
                }
            }
        }
    }

    /**
     * Convert a single chapter to CBZ (used during download)
     */
    suspend fun convertSingleChapter(
        mangaId: Long,
        chapterId: Long,
        imagePaths: List<String>
    ): String? {
        return try {
            cbzManager.convertFilesToCbz(mangaId, chapterId, imagePaths)
        } catch (e: Exception) {
            android.util.Log.e("CbzConversion", "Failed to convert chapter $chapterId", e)
            null
        }
    }

    fun clearError() {
        _conversionProgress.value = _conversionProgress.value.copy(
            error = null,
            successMessage = null,
            wasStopped = false
        )
    }
}