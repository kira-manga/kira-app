package me.manga.kira.data.download.di

import me.manga.kira.platform.download.BgDownloadLog
import me.manga.kira.presentation.features.download.DownloadEngineFlags
import me.manga.kira.presentation.features.download.domain.clean.BackgroundUrlSessionDownloadRepository
import me.manga.kira.presentation.features.download.domain.clean.ChapterFinalizer
import me.manga.kira.presentation.features.download.domain.clean.ChapterPageResolver
import me.manga.kira.presentation.features.download.domain.clean.CoroutineDownloadRepositoryImpl
import me.manga.kira.presentation.features.download.domain.clean.DownloadManifestStore
import me.manga.kira.presentation.features.download.domain.clean.DownloadRepository
import org.koin.core.module.Module
import org.koin.dsl.module

// iOS download engine. The M1 collaborators (page-URL/header resolution + terminal CBZ/bookkeeping
// step) are shared with the background engine. Rollback switch
// DownloadEngineFlags.IOS_BACKGROUND_ENGINE_ENABLED selects the background-URLSession engine (ON)
// vs the proven coroutine engine (OFF). The IosBackgroundScheduler / BackgroundTransport /
// BackgroundWorkSignal facades stay in platformModule().ios and resolve via get().
actual fun downloadModule(): Module = module {
    single { ChapterPageResolver(mangaDao = get(), chapterPageProvider = get(), sourcesRepository = get()) }
    single {
        ChapterFinalizer(
            dao = get(),
            libraryRepository = get(),
            notificationDao = get(),
            appFileSystem = get(),
            cbzWriter = get(),
            dataStore = get(),
        )
    }
    single { DownloadManifestStore(get()) }
    single<DownloadRepository> {
        BgDownloadLog.log(
            "engine.selected",
            "engine" to if (DownloadEngineFlags.IOS_BACKGROUND_ENGINE_ENABLED) "BackgroundUrlSession" else "CoroutineLegacy",
            "flag" to DownloadEngineFlags.IOS_BACKGROUND_ENGINE_ENABLED,
        )
        if (DownloadEngineFlags.IOS_BACKGROUND_ENGINE_ENABLED) {
            BackgroundUrlSessionDownloadRepository(
                dao = get(),
                chapterPageResolver = get(),
                chapterFinalizer = get(),
                manifestStore = get(),
                appFileSystem = get(),
                transport = get(),
                applicationScope = get(),
                downloadNotifier = get(),
                dataStoreHelper = get(),
                backgroundScheduler = get(),
                workSignal = get(),
            )
        } else {
            CoroutineDownloadRepositoryImpl(
                dao = get(),
                httpClient = get(),
                applicationScope = get(),
                appFileSystem = get(),
                downloadNotifier = get(),
                backgroundGuard = get(),
                chapterPageResolver = get(),
                chapterFinalizer = get(),
            )
        }
    }
}
