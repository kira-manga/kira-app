package me.manga.kira.data.download.di

import me.manga.kira.presentation.features.download.domain.clean.ChapterFinalizer
import me.manga.kira.presentation.features.download.domain.clean.ChapterPageResolver
import me.manga.kira.presentation.features.download.domain.clean.CoroutineDownloadRepositoryImpl
import me.manga.kira.presentation.features.download.domain.clean.DownloadRepository
import org.koin.core.module.Module
import org.koin.dsl.module

// Desktop download engine: the shared non-Android coroutine queue (CoroutineDownloadRepositoryImpl),
// reusing the M1 ChapterPageResolver + ChapterFinalizer collaborators. Downloads pages with the
// Koin-injected Ktor HttpClient (CIO engine) into AppFileSystem.chapterDir.
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
    single<DownloadRepository> {
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
