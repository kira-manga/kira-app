package me.manga.kira.sources.legacy.di

import me.manga.kira.presentation.features.library.domain.LibraryRepository
import me.manga.kira.presentation.features.repo_settings.domain.SourcesRepository
import me.manga.kira.sources_repositry.BaseMangaRepository
import org.koin.core.module.Module
import org.koin.dsl.module

/**
 * Persistence facades retained while their Room-facing contracts are moved out of this module.
 *
 * The compiled scraper classes remain source-compatible for saved local data, but none is bound
 * into the runtime graph. An empty repository set is deliberate: a source can be resolved only
 * from the active, verified generic catalog.
 */
fun sourcePersistenceModule(): Module =
    module {
        single<Set<BaseMangaRepository>> { emptySet() }
        single { SourcesRepository(get(), get<Set<BaseMangaRepository>>(), get(), get()) }
        single { LibraryRepository(get(), get(), get(), get(), get(), get()) }
    }
