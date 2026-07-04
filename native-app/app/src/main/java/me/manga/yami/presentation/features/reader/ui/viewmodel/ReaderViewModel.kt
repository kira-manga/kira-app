package me.manga.yamiapk.presentation.features.reader.ui.viewmodel

import android.content.Context
import android.util.Log
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.core.graphics.scale
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import coil3.Bitmap
import coil3.request.ImageRequest
import coil3.toBitmap
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.manga.yamiapk.core.cbz.CbzManager
import me.manga.yamiapk.core.states.State
import me.manga.yamiapk.domain.model.ReaderChapters
import me.manga.yamiapk.presentation.features.library.domain.LibraryRepository
import me.manga.yamiapk.presentation.features.reader.data.CompressionState
import me.manga.yamiapk.presentation.features.reader.data.ReaderItem
import me.manga.yamiapk.presentation.features.reader.data.ReadingMode
import me.manga.yamiapk.presentation.features.repo_settings.domain.SourcesRepository
import me.manga.yamiapk.presentation.features.settings.domain.SettingsRepository
import me.manga.yamiapk.presentation.features.statistics.domain.StatisticsRepository
import me.manga.yamiapk.sources_repositry.EmptyMangaRepository
import me.manga.yamiapk.sources_repositry.data.MangaSource
import javax.inject.Inject
import kotlin.math.sqrt

@HiltViewModel
class ReaderViewModel @Inject constructor(
    private val settingsRepo: SettingsRepository,
    private val statisticsRepo: StatisticsRepository,
    private val libraryRepository: LibraryRepository,
    private val sourcesRepository: SourcesRepository,
    private val cbzManager: CbzManager

) : ViewModel() {
    private val currentRepoFlow = sourcesRepository.activeRepo
        .stateIn(
            scope       = viewModelScope,
            started     = SharingStarted.Eagerly,
            initialValue= EmptyMangaRepository
        )

    private var currentChaptersList: List<ReaderChapters> = emptyList()

    private val _loadingChapters = MutableStateFlow<Set<Int>>(emptySet())
    val loadingChapters: StateFlow<Set<Int>> = _loadingChapters.asStateFlow()

    private val _allReaderItems = MutableStateFlow<List<ReaderItem>>(emptyList())
    val allReaderItems: StateFlow<List<ReaderItem>> = _allReaderItems.asStateFlow()

    private val _readingMode = MutableStateFlow(ReadingMode.DEFAULT)
    val readingMode: StateFlow<ReadingMode> = _readingMode.asStateFlow()

    private val _bookmarked = MutableStateFlow(false)
    val bookmarked: StateFlow<Boolean> = _bookmarked.asStateFlow()

    private val _loadedChapterIndexes = MutableStateFlow<List<Int>>(emptyList())
    val loadedChapterIndexes: StateFlow<List<Int>> = _loadedChapterIndexes.asStateFlow()

    private val _currentChapterIndex = MutableStateFlow(0)
    val currentChapterIndex: StateFlow<Int> = _currentChapterIndex.asStateFlow()

    private val _compressionStates = MutableStateFlow<Map<String, CompressionState>>(emptyMap())
    val compressionStates: StateFlow<Map<String, CompressionState>> = _compressionStates.asStateFlow()

    private val activeCompressionJobs = mutableMapOf<String, Job>()
    private val inProgressLoads = mutableSetOf<Int>()

    // NEW: Track streaming chapters
    private val addedStreamingUrls = mutableMapOf<Int, MutableSet<String>>()

    init {
        viewModelScope.launch {
            settingsRepo.readingModeFlow
                .distinctUntilChanged()
                .collect { modeString ->
                    _readingMode.value = ReadingMode.valueOf(modeString)
                }
        }
    }

    fun setCurrentChapterIndex(newIndex: Int) {
        if (_currentChapterIndex.value != newIndex) {
            _currentChapterIndex.value = newIndex
        }
    }

    fun initialize(
        startIndex: Int,
        chaptersList: List<ReaderChapters>,
        mangaApi: String,
        screenWidthPx: Int,
        context: Context
    ) {
        if (_loadedChapterIndexes.value.isNotEmpty()) return

        currentChaptersList = chaptersList
        val safeIndex = startIndex.coerceIn(chaptersList.indices)

        loadChapter(
            index = safeIndex,
            chaptersList = chaptersList,
            mangaApi = mangaApi,
            screenWidthPx = screenWidthPx,
            context = context
        )

        _loadedChapterIndexes.value = listOf(safeIndex)
        _currentChapterIndex.value = safeIndex
    }

    /**
     * Main loadChapter - routes to streaming or normal based on API
     */
    fun loadChapter(
        index: Int,
        chaptersList: List<ReaderChapters>,
        mangaApi: String,
        screenWidthPx: Int,
        context: Context,
    ) {
        // Route ProManga to streaming handler
        if (mangaApi == MangaSource.PROCHAN.API) {
            loadChapterStreaming(index, chaptersList, mangaApi, screenWidthPx, context)
            return
        }

        // Original logic for other sources
        if (inProgressLoads.contains(index)) return
        inProgressLoads.add(index)
        _loadingChapters.update { it + index }

        viewModelScope.launch {
            if (currentChaptersList != chaptersList) {
                currentChaptersList = chaptersList
            }

            val currentChapter = chaptersList.getOrElse(index) {
                ReaderChapters(
                    chapterNumber = "",
                    chapterName = "",
                    isDownloaded = false,
                    url = "",
                    isBookmarked = false,
                    chapterId = 0,
                    mangaId = 0,
                    mangaName = "",
                    localImagePaths = emptyList(),
                    api = "",
                    language = ""
                )
            }

            val imagesFlow: Flow<State<List<String>>> = if (
                currentChapter.isDownloaded &&
                currentChapter.localImagePaths.isNotEmpty()
            ) {
                flow {
                    val paths = currentChapter.localImagePaths
                    if (paths.size == 1 && paths.first().endsWith(".cbz")) {
                        val cbzPath = paths.first()
                        val extractedPaths = cbzManager.extractImagesFromCbz(
                            cbzPath,
                            currentChapter.mangaId,
                            currentChapter.chapterId
                        )
                        emit(State.Success(extractedPaths))
                    } else {
                        emit(State.Success(paths))
                    }
                }
            } else {
                val repoImpl = sourcesRepository.getRepoByName(mangaApi)
                repoImpl.fetchChapterDataF(currentChapter.url)
            }

            // Collect first emission only
            imagesFlow.collect { state ->
                when (state) {
                    is State.Success -> {
                        val urls = state.toData() ?: emptyList()

                        val requests: List<ImageRequest> = urls.map { url ->
                            sourcesRepository.getRepoByName(mangaApi)
                                .buildImageRequest(context, url, screenWidthPx)
                        }

                        val itemsForThisChapter = requests.map { req ->
                            ReaderItem.ImagePage(request = req, chapterIndex = index)
                        }.toMutableList<ReaderItem>()

                        val nextIdx = (index + 1).takeIf { it <= chaptersList.lastIndex }
                        if (nextIdx != null) {
                            itemsForThisChapter.add(
                                ReaderItem.NextChapterOverlay(
                                    currentChapter = currentChapter,
                                    nextChapter = chaptersList[nextIdx]
                                )
                            )
                        } else {
                            itemsForThisChapter.add(
                                ReaderItem.ErrorOverlay(
                                    currentChapter = currentChapter,
                                    errorMassage = "No Next Chapter",
                                    errorCode = 0

                                )
                            )
                        }

                        inProgressLoads.remove(index)
                        _loadingChapters.update { it - index }
                        _allReaderItems.update { oldList -> oldList + itemsForThisChapter }
                        return@collect
                    }

                    is State.Error -> {
                        val itemsForThisChapter = mutableListOf<ReaderItem>()
                        val nextIdx = (index + 1).takeIf { it <= chaptersList.lastIndex }
                        if (nextIdx != null) {
                            itemsForThisChapter.add(
                                ReaderItem.ErrorOverlay(
                                    currentChapter = currentChapter,
                                    errorMassage = state.message,
                                    errorCode = state.code
                                )
                            )
                            itemsForThisChapter.add(
                                ReaderItem.NextChapterOverlay(
                                    currentChapter = currentChapter,
                                    nextChapter = chaptersList[nextIdx]
                                )
                            )
                        } else {
                            itemsForThisChapter.add(
                                ReaderItem.ErrorOverlay(
                                    currentChapter = currentChapter,
                                    errorMassage = "No Next Chapter",
                                    errorCode = state.code
                                )
                            )
                        }

                        inProgressLoads.remove(index)
                        _loadingChapters.update { it - index }
                        _allReaderItems.update { oldList -> oldList + itemsForThisChapter }
                        return@collect
                    }

                    else -> {
                        // Loading - continue
                    }
                }
            }
        }
    }

    /**
     * NEW: Special streaming handler for ProManga
     * Adds only NEW images as they arrive
     */
    private fun loadChapterStreaming(
        index: Int,
        chaptersList: List<ReaderChapters>,
        mangaApi: String,
        screenWidthPx: Int,
        context: Context,
    ) {
        if (inProgressLoads.contains(index)) return
        inProgressLoads.add(index)
        _loadingChapters.update { it + index }

        // Initialize URL tracking for this chapter
        addedStreamingUrls[index] = mutableSetOf()

        viewModelScope.launch {
            if (currentChaptersList != chaptersList) {
                currentChaptersList = chaptersList
            }

            val currentChapter = chaptersList.getOrElse(index) {
                ReaderChapters(
                    chapterNumber = "",
                    chapterName = "",
                    isDownloaded = false,
                    url = "",
                    isBookmarked = false,
                    chapterId = 0,
                    mangaId = 0,
                    mangaName = "",
                    localImagePaths = emptyList(),
                    api = "",
                    language = ""
                )
            }

            val imagesFlow: Flow<State<List<String>>> = if (
                currentChapter.isDownloaded &&
                currentChapter.localImagePaths.isNotEmpty()
            ) {
                flow {
                    val paths = currentChapter.localImagePaths
                    if (paths.size == 1 && paths.first().endsWith(".cbz")) {
                        val cbzPath = paths.first()
                        val extractedPaths = cbzManager.extractImagesFromCbz(
                            cbzPath,
                            currentChapter.mangaId,
                            currentChapter.chapterId
                        )
                        emit(State.Success(extractedPaths))
                    } else {
                        emit(State.Success(paths))
                    }
                }
            } else {
                val repoImpl = sourcesRepository.getRepoByName(mangaApi)
                repoImpl.fetchChapterDataF(currentChapter.url)
            }

            var isFirstEmission = true
            val trackedUrls = addedStreamingUrls[index] ?: mutableSetOf()
            var lastEmissionSize = 0

            imagesFlow.collect { state ->
                when (state) {
                    is State.Success -> {
                        val allUrls = state.toData() ?: emptyList()

                        // Only process if we got new URLs
                        if (allUrls.size <= lastEmissionSize) {
                            return@collect
                        }
                        lastEmissionSize = allUrls.size

                        // Find NEW URLs only
                        val newUrls = allUrls.filter { url -> !trackedUrls.contains(url) }

                        if (newUrls.isNotEmpty()) {
                            Log.d("ReaderViewModel", "Chapter[$index]: Adding ${newUrls.size} new images (${trackedUrls.size} → ${trackedUrls.size + newUrls.size})")

                            // Mark as tracked
                            trackedUrls.addAll(newUrls)

                            // Build requests for NEW URLs only
                            val newRequests: List<ImageRequest> = newUrls.map { url ->
                                sourcesRepository.getRepoByName(mangaApi)
                                    .buildImageRequest(context, url, screenWidthPx)
                            }

                            val newItems = newRequests.map { req ->
                                ReaderItem.ImagePage(request = req, chapterIndex = index)
                            }

                            if (isFirstEmission) {
                                isFirstEmission = false
                                val itemsWithOverlay = newItems.toMutableList<ReaderItem>()

                                val nextIdx = (index + 1).takeIf { it <= chaptersList.lastIndex }
                                if (nextIdx != null) {
                                    itemsWithOverlay.add(
                                        ReaderItem.NextChapterOverlay(
                                            currentChapter = currentChapter,
                                            nextChapter = chaptersList[nextIdx]
                                        )
                                    )
                                } else {
                                    itemsWithOverlay.add(
                                        ReaderItem.ErrorOverlay(
                                            currentChapter = currentChapter,
                                            errorMassage = "No Next Chapter",
                                            errorCode = 0
                                        )
                                    )
                                }

                                _allReaderItems.update { oldList -> oldList + itemsWithOverlay }
                            } else {
                                // Insert before overlay
                                _allReaderItems.update { oldList ->
                                    val mutableList = oldList.toMutableList()

                                    val overlayIndex = mutableList.indexOfLast { item ->
                                        when (item) {
                                            is ReaderItem.NextChapterOverlay ->
                                                item.currentChapter.url == currentChapter.url
                                            is ReaderItem.ErrorOverlay ->
                                                item.currentChapter.url == currentChapter.url
                                            else -> false
                                        }
                                    }

                                    if (overlayIndex != -1) {
                                        mutableList.addAll(overlayIndex, newItems)
                                    } else {
                                        mutableList.addAll(newItems)
                                    }

                                    mutableList
                                }
                            }
                        }

                        // Check if complete
                        if (allUrls.size == trackedUrls.size && allUrls.isNotEmpty()) {
                            Log.d("ReaderViewModel", "Chapter[$index] COMPLETE: ${allUrls.size} total images")
                            inProgressLoads.remove(index)
                            _loadingChapters.update { it - index }
                            addedStreamingUrls.remove(index)
                        }
                    }

                    is State.Error -> {
                        if (isFirstEmission) {
                            val itemsForThisChapter = mutableListOf<ReaderItem>()
                            val nextIdx = (index + 1).takeIf { it <= chaptersList.lastIndex }
                            if (nextIdx != null) {
                                itemsForThisChapter.add(
                                    ReaderItem.ErrorOverlay(
                                        currentChapter = currentChapter,
                                        errorMassage = state.message,
                                        errorCode = state.code
                                    )
                                )
                                itemsForThisChapter.add(
                                    ReaderItem.NextChapterOverlay(
                                        currentChapter = currentChapter,
                                        nextChapter = chaptersList[nextIdx]
                                    )
                                )
                            } else {
                                itemsForThisChapter.add(
                                    ReaderItem.ErrorOverlay(
                                        currentChapter = currentChapter,
                                        errorMassage = "No Next Chapter",
                                        errorCode = state.code
                                    )
                                )
                            }
                            _allReaderItems.update { oldList -> oldList + itemsForThisChapter }
                        }

                        inProgressLoads.remove(index)
                        _loadingChapters.update { it - index }
                        addedStreamingUrls.remove(index)
                    }

                    else -> {
                        // Loading
                    }
                }
            }
        }
    }

    fun isChapterLoading(index: Int): Boolean =
        _loadingChapters.value.contains(index)

    fun goToNextChapter(
        chaptersList: List<ReaderChapters>,
        mangaApi: String,
        screenWidthPx: Int,
        context: Context
    ) {
        currentChaptersList = chaptersList
        val curr = _currentChapterIndex.value
        if (curr >= chaptersList.lastIndex) return

        val nextIdx = curr + 1
        if (!_loadedChapterIndexes.value.contains(nextIdx)) {
            _loadedChapterIndexes.value = _loadedChapterIndexes.value + nextIdx
            loadChapter(nextIdx, chaptersList, mangaApi, screenWidthPx, context)
        }
        _currentChapterIndex.value = nextIdx
    }

    fun goToChapter(
        index: Int,
        chaptersList: List<ReaderChapters>,
        mangaApi: String,
        screenWidthPx: Int,
        context: Context
    ) {
        currentChaptersList = chaptersList
        val safeIndex = index.coerceIn(chaptersList.indices)

        _allReaderItems.value = emptyList()
        _loadedChapterIndexes.value = listOf(safeIndex)
        _currentChapterIndex.value = safeIndex

        loadChapter(
            index = safeIndex,
            chaptersList = chaptersList,
            mangaApi = mangaApi,
            screenWidthPx = screenWidthPx,
            context = context
        )
    }

    fun goToPreviousChapter(
        chaptersList: List<ReaderChapters>,
        mangaApi: String,
        screenWidthPx: Int,
        context: Context,
    ) {
        currentChaptersList = chaptersList
        val curr = _currentChapterIndex.value
        if (curr <= 0) return

        val prevIdx = curr - 1
        val loaded = _loadedChapterIndexes.value.toMutableList()
        if (loaded.contains(prevIdx)) {
            while (loaded.last() > prevIdx) {
                loaded.removeAt(loaded.lastIndex)
            }
            _loadedChapterIndexes.value = loaded
        } else {
            _loadedChapterIndexes.value = loaded + prevIdx
            loadChapter(prevIdx, chaptersList, mangaApi, screenWidthPx, context)
        }
        _currentChapterIndex.value = prevIdx
    }

    fun observeBookmark(chapterId: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            libraryRepository.isChapterBookmarkedFlow(chapterId)
                .collect { bookmarkedNow -> _bookmarked.value = bookmarkedNow }
        }
    }

    fun toggleChapterBookmark(chapterId: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            libraryRepository.toggleChapterBookmark(chapterId)
        }
    }

    fun clearList(){
        _allReaderItems.value = listOf()
    }

    fun setReadingMode(mode: ReadingMode) {
        viewModelScope.launch {
            _readingMode.value = mode
            settingsRepo.setReadingMode(mode.name)
        }
    }

    fun onScreenResume() {
        statisticsRepo.startReadingSession()
    }

    fun onScreenPause() {
        viewModelScope.launch(Dispatchers.Default) {
            statisticsRepo.endReadingSession()
        }
    }

    fun updateReaderItem(index: Int, newItem: ReaderItem) {
        val currentItems = _allReaderItems.value.toMutableList()
        if (index < currentItems.size) {
            currentItems[index] = newItem
            _allReaderItems.value = currentItems
        }
    }

    fun startImageCompression(
        imageUrl: String,
        absIndex: Int,
        img: coil3.Image,
        maxBytes: Long,
        screenWidthPx: Int
    ) {
        val currentState = _compressionStates.value[imageUrl]
        if (currentState?.isCompressing == true || currentState?.isCompleted == true) {
            return
        }

        activeCompressionJobs[imageUrl]?.cancel()

        _compressionStates.update { states ->
            states + (imageUrl to CompressionState(isCompressing = true))
        }

        val job = viewModelScope.launch {
            try {
                val compressedBitmap = withContext(Dispatchers.Default) {
                    val originalBitmap = img.toBitmap()
                    compressImageToSizeOptimized(originalBitmap, maxBytes, screenWidthPx)
                }

                val compressedPainter = BitmapPainter(
                    compressedBitmap.asImageBitmap()
                )

                val currentItems = _allReaderItems.value.toMutableList()
                if (absIndex < currentItems.size && currentItems[absIndex] is ReaderItem.ImagePage) {
                    val originalItem = currentItems[absIndex] as ReaderItem.ImagePage
                    currentItems[absIndex] = originalItem.copy(
                        isCompressed = true,
                        compressedPainter = compressedPainter
                    )
                    _allReaderItems.value = currentItems
                }

                _compressionStates.update { states ->
                    states + (imageUrl to CompressionState(isCompleted = true))
                }

                Log.i("ImageCompression", "Successfully compressed item at index $absIndex")

            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) {
                    Log.i("ImageCompression", "Compression cancelled for item at index $absIndex")
                    _compressionStates.update { states ->
                        states - imageUrl
                    }
                } else {
                    Log.e("ImageCompression", "Failed to compress image", e)
                    _compressionStates.update { states ->
                        states + (imageUrl to CompressionState(error = "Compression failed: ${e.message}"))
                    }
                }
            } finally {
                activeCompressionJobs.remove(imageUrl)
            }
        }

        activeCompressionJobs[imageUrl] = job
    }

    fun retryCompression(imageUrl: String) {
        _compressionStates.update { states ->
            states - imageUrl
        }
    }

    override fun onCleared() {
        super.onCleared()
        activeCompressionJobs.values.forEach { it.cancel() }
        activeCompressionJobs.clear()
        addedStreamingUrls.clear()

        viewModelScope.launch(Dispatchers.IO) {
            _loadedChapterIndexes.value.forEach { index ->
                currentChaptersList.getOrNull(index)?.let { chapter ->
                    cbzManager.cleanupExtractedCache(
                        chapter.mangaId,
                        chapter.chapterId
                    )
                }
            }
        }
    }

    suspend fun compressImageToSizeOptimized(
        bitmap: Bitmap,
        maxSizeBytes: Long = 100L * 1024 * 1024,
        screenWidthPx: Int
    ): Bitmap = withContext(Dispatchers.Default) {
        val currentSize = bitmap.allocationByteCount.toLong()

        if (currentSize <= maxSizeBytes) {
            return@withContext bitmap
        }

        val widthScale = screenWidthPx.toFloat() / bitmap.width.toFloat()
        val targetWidth = minOf(screenWidthPx, bitmap.width)
        val targetHeight = (bitmap.height * widthScale).toInt()

        val dimensionScaledSize = (targetWidth * targetHeight * 4).toLong()
        val scaleFactor = if (dimensionScaledSize > maxSizeBytes) {
            sqrt(maxSizeBytes.toDouble() / dimensionScaledSize.toDouble()).toFloat()
        } else {
            1f
        }

        val finalWidth = (targetWidth * scaleFactor).toInt().coerceAtLeast(100)
        val finalHeight = (targetHeight * scaleFactor).toInt().coerceAtLeast(100)

        Log.i("ImageCompression", "Original: ${bitmap.width}x${bitmap.height} (${currentSize} bytes)")
        Log.i("ImageCompression", "Target: ${finalWidth}x${finalHeight}")

        bitmap.scale(finalWidth, finalHeight, filter = true)
    }
}