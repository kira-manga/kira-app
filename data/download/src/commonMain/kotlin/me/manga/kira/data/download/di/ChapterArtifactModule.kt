package me.manga.kira.data.download.di

import me.manga.kira.data.download.artifacts.ChapterArtifactRecovery
import me.manga.kira.data.download.artifacts.ChapterArtifacts
import me.manga.kira.data.download.artifacts.ChapterDownloadArtifacts
import org.koin.dsl.module

/** Included exactly once by each host's download module; all consumers share these file gates. */
internal fun chapterArtifactModule() = module {
    single { ChapterArtifactRecovery(get(), get(), get(), get()) }
    single { ChapterArtifacts(get(), get()) }
    single { ChapterDownloadArtifacts(get(), get(), get(), get(), get()) }
}
