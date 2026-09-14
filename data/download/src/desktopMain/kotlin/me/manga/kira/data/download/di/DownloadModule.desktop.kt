package me.manga.kira.data.download.di

import me.manga.kira.presentation.features.download.domain.clean.ChapterCompletionRecords
import me.manga.kira.presentation.features.download.domain.clean.ChapterDownloadStages
import me.manga.kira.presentation.features.download.domain.clean.ChapterFinalizer
import me.manga.kira.presentation.features.download.domain.clean.ChapterPageResolver
import me.manga.kira.presentation.features.download.domain.clean.CoroutineDownloadHost
import me.manga.kira.presentation.features.download.domain.clean.CoroutineDownloadRepositoryImpl
import me.manga.kira.presentation.features.download.domain.clean.DownloadRepository
import me.manga.kira.presentation.features.download.domain.clean.PageDownloadTransfer
import org.koin.core.module.Module
import org.koin.core.qualifier.named
import org.koin.dsl.module

// Desktop download engine: the shared non-Android coroutine queue (CoroutineDownloadRepositoryImpl),
// reusing the M1 ChapterPageResolver + ChapterFinalizer collaborators. Downloads pages with the
// Koin-injected Ktor HttpClient (CIO engine) into AppFileSystem.chapterDir.
actual fun downloadModule(): Module =
    module {
        single { ChapterPageResolver(mangaDao = get(), chapterPageProvider = get()) }
        factory { ChapterCompletionRecords(get(), get(), get()) }
        single {
            ChapterFinalizer(
                records = get(),
                appFileSystem = get(),
                cbzWriter = get(),
                dataStore = get(),
                mediaInspector = get(),
            )
        }
        factory { ChapterDownloadStages(get(), get()) }
        factory { PageDownloadTransfer(get(named("chapter-download-http")), get()) }
        factory { CoroutineDownloadHost(get(), get(), get()) }
        single<DownloadRepository> {
            CoroutineDownloadRepositoryImpl(
                dao = get(),
                appFileSystem = get(),
                pageTransfer = get(),
                host = get(),
                stages = get(),
            )
        }
    }
