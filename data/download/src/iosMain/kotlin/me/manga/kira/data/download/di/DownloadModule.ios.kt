package me.manga.kira.data.download.di

import me.manga.kira.platform.download.BgDownloadLog
import me.manga.kira.presentation.features.download.DownloadEngineFlags
import me.manga.kira.presentation.features.download.domain.clean.BackgroundDownloadHost
import me.manga.kira.presentation.features.download.domain.clean.BackgroundDownloadStorage
import me.manga.kira.presentation.features.download.domain.clean.BackgroundPageTransfer
import me.manga.kira.presentation.features.download.domain.clean.BackgroundUrlSessionDownloadRepository
import me.manga.kira.presentation.features.download.domain.clean.ChapterCompletionRecords
import me.manga.kira.presentation.features.download.domain.clean.ChapterDownloadStages
import me.manga.kira.presentation.features.download.domain.clean.ChapterFinalizer
import me.manga.kira.presentation.features.download.domain.clean.ChapterPageResolver
import me.manga.kira.presentation.features.download.domain.clean.CoroutineDownloadHost
import me.manga.kira.presentation.features.download.domain.clean.CoroutineDownloadRepositoryImpl
import me.manga.kira.presentation.features.download.domain.clean.DownloadManifestStore
import me.manga.kira.presentation.features.download.domain.clean.DownloadRepository
import me.manga.kira.presentation.features.download.domain.clean.PageDownloadTransfer
import org.koin.core.module.Module
import org.koin.core.qualifier.named
import org.koin.dsl.module

// iOS download engine. The M1 collaborators (page-URL/header resolution + terminal CBZ/bookkeeping
// step) are shared with the background engine. Rollback switch
// DownloadEngineFlags.IOS_BACKGROUND_ENGINE_ENABLED selects the background-URLSession engine (ON)
// vs the proven coroutine engine (OFF). The IosBackgroundScheduler / BackgroundTransport /
// BackgroundWorkSignal facades stay in platformModule().ios and resolve via get().
actual fun downloadModule(): Module =
    module {
        includes(chapterArtifactModule(recoverNative = DownloadEngineFlags.IOS_BACKGROUND_ENGINE_ENABLED))
        single { ChapterPageResolver(mangaDao = get(), chapterPageProvider = get()) }
        factory { ChapterCompletionRecords(get(), get(), get(), get()) }
        single {
            ChapterFinalizer(
                records = get(),
                appFileSystem = get(),
                cbzWriter = get(),
                dataStore = get(),
                mediaInspector = get(),
            )
        }
        single { DownloadManifestStore(get()) }
        factory { ChapterDownloadStages(get(), get()) }
        factory { PageDownloadTransfer(get(named("chapter-download-http")), get()) }
        factory { CoroutineDownloadHost(get(), get(), get()) }
        factory { BackgroundDownloadStorage(get(), get(), get()) }
        factory { BackgroundPageTransfer(get(), get()) }
        factory { BackgroundDownloadHost(get(), get(), get(), get()) }
        single<DownloadRepository> {
            val engine =
                if (DownloadEngineFlags.IOS_BACKGROUND_ENGINE_ENABLED) {
                    "BackgroundUrlSession"
                } else {
                    "CoroutineLegacy"
                }
            BgDownloadLog.log(
                "engine.selected",
                "engine" to engine,
                "flag" to DownloadEngineFlags.IOS_BACKGROUND_ENGINE_ENABLED,
            )
            if (DownloadEngineFlags.IOS_BACKGROUND_ENGINE_ENABLED) {
                BackgroundUrlSessionDownloadRepository(
                    storage = get(),
                    stages = get(),
                    artifacts = get(),
                    pageTransfer = get(),
                    host = get(),
                    dataStoreHelper = get(),
                    operations = get(),
                    catalog = get(),
                )
            } else {
                CoroutineDownloadRepositoryImpl(
                    dao = get(),
                    appFileSystem = get(),
                    pageTransfer = get(),
                    host = get(),
                    stages = get(),
                    artifacts = get(),
                    operations = get(),
                    catalog = get(),
                )
            }
        }
    }
