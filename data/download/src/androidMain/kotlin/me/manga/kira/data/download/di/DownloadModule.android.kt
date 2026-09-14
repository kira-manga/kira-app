package me.manga.kira.data.download.di

import me.manga.kira.presentation.features.download.domain.ChapterDownloadPersistence
import me.manga.kira.presentation.features.download.domain.ChapterDownloadService
import me.manga.kira.presentation.features.download.domain.clean.DownloadRepository
import me.manga.kira.presentation.features.download.domain.clean.DownloadRepositoryImpl
import org.koin.android.ext.koin.androidContext
import org.koin.core.module.Module
import org.koin.core.qualifier.named
import org.koin.dsl.module

// Android download engine: WorkManager-backed DownloadRepositoryImpl driving DownloadWorkerV2, with
// ChapterDownloadService doing the per-chapter fetch/CBZ work. WorkManager + OptimizedCbzManager
// stay bound in platformModule().android (general Android facilities) and resolve via get().
actual fun downloadModule(): Module =
    module {
        single { ChapterDownloadPersistence(get(), get(), get(), get()) }
        single {
            ChapterDownloadService(
                context = androidContext(),
                persistence = get(),
                httpClient = get(named("chapter-download-http")),
                optimizedCbzManager = get(),
                dataStoreHelper = get(),
                mediaInspector = get(),
            )
        }
        single<DownloadRepository> {
            DownloadRepositoryImpl(
                workManager = get(),
                dao = get(),
                chapterDownloadService = get(),
            )
        }
    }
