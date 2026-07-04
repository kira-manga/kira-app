package me.manga.kira.data.download.di

import me.manga.kira.presentation.features.download.domain.ChapterDownloadService
import me.manga.kira.presentation.features.download.domain.clean.DownloadRepository
import me.manga.kira.presentation.features.download.domain.clean.DownloadRepositoryImpl
import org.koin.android.ext.koin.androidContext
import org.koin.core.module.Module
import org.koin.dsl.module

// Android download engine: WorkManager-backed DownloadRepositoryImpl driving DownloadWorkerV2, with
// ChapterDownloadService doing the per-chapter fetch/CBZ work. WorkManager + OptimizedCbzManager
// stay bound in platformModule().android (general Android facilities) and resolve via get().
actual fun downloadModule(): Module = module {
    single {
        ChapterDownloadService(
            context = androidContext(),
            libraryRepository = get(),
            httpClient = get(),
            fileService = get(),
            notificationDao = get(),
            chapterDownloadDao = get(),
            optimizedCbzManager = get(),
            dataStoreHelper = get(),
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
