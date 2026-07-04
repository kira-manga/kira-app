package me.manga.yamiapk.presentation.features.download.domain

import android.util.Log
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapConcat
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.withContext
import me.manga.yamiapk.core.states.State
import me.manga.yamiapk.data.local.dao.ChapterDao
import me.manga.yamiapk.data.local.dao.ChapterDownloadDao
import me.manga.yamiapk.data.local.dao.MangaDao
import me.manga.yamiapk.data.local.entity.SavedChapterEntity
import me.manga.yamiapk.presentation.features.download.data.DownloadState
import me.manga.yamiapk.presentation.features.download.data.DownloadingState
import me.manga.yamiapk.presentation.features.repo_settings.domain.SourcesRepository
import me.manga.yamiapk.sources_repositry.BaseMangaRepository
import me.manga.yamiapk.sources_repositry.ar.promanga.ProMangaRepository
import me.manga.yamiapk.sources_repositry.ar.promanga.ProchanRepository
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DownloadRepository @Inject constructor(
    private val chapterDownloadService: ChapterDownloadService,
    private val mangaDao: MangaDao,
    private val chapterDao: ChapterDao,
    private val chapterDownloadDao: ChapterDownloadDao,
    private val sourcesRepository: SourcesRepository

    ) {

    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO


    suspend fun provideActiveRepo(mangaId: Long): BaseMangaRepository =
        withContext(ioDispatcher) {
            val api = mangaDao.getApiByLocalId(mangaId)
            if (api.isNullOrBlank()) {
                sourcesRepository.activeRepo
                    .first()
            } else {
                // assuming you have a method like getRepoForApi:
                sourcesRepository.getRepoByName(api)
            }
        }



    suspend fun downloadChapterFlowv2(chapter: SavedChapterEntity): Flow<DownloadState> {
        val repo = provideActiveRepo(chapter.mangaId)

        // Choose the appropriate fetch method based on repository type
        val chapterDataFlow = if (repo is ProchanRepository) {
            Log.d("DownloadRepository", "Using batch loading for ProManga")
            repo.getFullImgs(chapter.url)
        } else {
            Log.d("DownloadRepository", "Using standard streaming for ${repo.API}")
            repo.fetchChapterDataF(chapter.url)
        }

        return chapterDataFlow
            .onStart {
                repo.initSite()
            }
            .flatMapConcat { state ->
                Log.i("DownloadRepository", "Fetch state: $state")

                when (state) {
                    is State.Loading -> flowOf(
                        DownloadState.InProgress(
                            totalImages = 0,
                            downloadedImages = 0,
                            currentImageUrl = ""
                        )
                    )

                    is State.Error -> flowOf(
                        DownloadState.Error(
                            exception = Throwable(state.message),
                            downloadedImages = 0,
                            totalImages = 0
                        )
                    )

                    is State.Success -> flow {
                        chapterDownloadService
                            .downloadChapterC(
                                chapter,
                                state.data,
                                repo
                            )
                            .collect { downloadState ->
                                Log.e("TAGasfafsdfasdfasdfasdfsaf", "downloadState state: $downloadState")

                                when (downloadState) {
                                    is DownloadState.Compressing -> {
                                        // Update database to show compressing state
                                        chapterDownloadDao.updateStateChId(
                                            chapter.id,
                                            DownloadingState.COMPRESSING
                                        )
                                        emit(downloadState)
                                    }

                                    is DownloadState.Complete -> {
                                        // Update to SUCCESS after compression completes
                                        chapterDownloadDao.updateStateAndProgress(
                                            chapter.id,
                                            DownloadingState.SUCCESS,
                                            100
                                        )
                                        chapterDao.markChapterDownloaded(chapter.id)
                                        emit(downloadState)
                                    }

                                    else -> {
                                        emit(downloadState)
                                    }
                                }
                            }
                    }
                }
            }
            .flowOn(ioDispatcher)
    }









// In DownloadRepository.kt - Update downloadChapterFlowv2 method

//    suspend fun downloadChapterFlowv2(chapter: SavedChapterEntity): Flow<DownloadState> =
//        provideActiveRepo(chapter.mangaId)
//            .fetchChapterDataF(chapter.url)
//            .onStart {
//                provideActiveRepo(chapter.mangaId).initSite()
//            }
//            .flatMapConcat { state ->
//                Log.i("DownloadRepository", "Fetch state: $state")
//
//                when (state) {
//                    is State.Loading -> flowOf(
//                        DownloadState.InProgress(
//                            totalImages = 0,
//                            downloadedImages = 0,
//                            currentImageUrl = ""
//                        )
//                    )
//
//                    is State.Error -> flowOf(
//                        DownloadState.Error(
//                            exception = Throwable(state.message),
//                            downloadedImages = 0,
//                            totalImages = 0
//                        )
//                    )
//
//                    is State.Success -> flow {
//                        chapterDownloadService
//                            .downloadChapterC(
//                                chapter,
//                                state.data,
//                                provideActiveRepo(chapter.mangaId)
//                            )
//                            .collect { downloadState ->
//                                Log.e("TAGasfafsdfasdfasdfasdfsaf", "downloadState state: $downloadState")
//
//                                when (downloadState) {
//                                    is DownloadState.Compressing -> {
//
//                                        // Update database to show compressing state
//                                        chapterDownloadDao.updateStateChId(
//                                            chapter.id,
//                                            DownloadingState.COMPRESSING
//                                        )
//                                        emit(downloadState)
//                                    }
//
//                                    is DownloadState.Complete -> {
//                                        // Update to SUCCESS after compression completes
//                                        chapterDownloadDao.updateStateAndProgress(
//                                            chapter.id,
//                                            DownloadingState.SUCCESS,
//                                            100
//                                        )
//                                        chapterDao.markChapterDownloaded(chapter.id)
//                                        emit(downloadState)
//                                    }
//
//                                    else -> {
//                                        emit(downloadState)
//                                    }
//                                }
//                            }
//                    }
//                }
//            }
//            .flowOn(ioDispatcher)





}