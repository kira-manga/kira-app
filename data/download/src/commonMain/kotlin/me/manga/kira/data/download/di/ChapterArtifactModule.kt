package me.manga.kira.data.download.di

import me.manga.kira.data.download.artifacts.ChapterArtifactRecovery
import me.manga.kira.data.download.artifacts.ChapterArtifacts
import me.manga.kira.data.download.artifacts.ChapterDownloadArtifacts
import me.manga.kira.platform.download.DownloadOperationExclusion
import org.koin.dsl.module

/** Included exactly once by each host's download module; all consumers share these file gates. */
internal fun chapterArtifactModule(recoverNative: Boolean = false) = module {
    if (recoverNative) {
        // The reservation exists before any caller can obtain this graph's exclusion. Lazy native
        // transport attachment must never leave a first-capture window for selection/removal.
        single { DownloadOperationExclusion.recovering() }
        single { get<DownloadOperationExclusion.Recovery>().exclusion }
    } else {
        single { DownloadOperationExclusion() }
    }
    single { ChapterArtifactRecovery(get(), get(), get(), get()) }
    single { ChapterArtifacts(get(), get()) }
    single { ChapterDownloadArtifacts(get(), get(), get(), get(), get()) }
}
