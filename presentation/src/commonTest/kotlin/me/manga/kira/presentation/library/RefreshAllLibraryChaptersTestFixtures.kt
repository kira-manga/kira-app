package me.manga.kira.presentation.library

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import me.manga.kira.core.dispatchers.DispatcherProvider
import me.manga.kira.core.result.AppResult
import me.manga.kira.domain.model.Chapter
import me.manga.kira.domain.model.Manga
import me.manga.kira.domain.model.MangaDetails
import me.manga.kira.domain.repository.LibraryRepository
import me.manga.kira.domain.repository.MangaDetailsRepository
import me.manga.kira.domain.usecase.details.FetchMangaDetailsUseCase
import me.manga.kira.domain.usecase.library.ObserveLibraryUseCase
import me.manga.kira.domain.usecase.library.PersistNewChaptersAndNotifyUseCase
import me.manga.kira.domain.usecase.library.RefreshAllLibraryChaptersUseCase
import me.manga.kira.presentation.testing.FakeLibraryRepository
import me.manga.kira.presentation.testing.sampleLibraryManga

@OptIn(ExperimentalCoroutinesApi::class)
private fun TestScope.dispatchers(): DispatcherProvider {
    val d = UnconfinedTestDispatcher(testScheduler)
    return object : DispatcherProvider {
        override val main = d
        override val mainImmediate = d
        override val default = d
        override val io = d
        override val unconfined = d
    }
}

internal fun details(
    manga: Manga,
    chapters: List<Chapter> = emptyList(),
) = MangaDetails(
    api = manga.api,
    language = manga.language,
    title = manga.title,
    url = manga.url,
    coverUrl = "cover",
    description = "",
    author = "",
    rating = "",
    status = "",
    genres = emptyList(),
    chapters = chapters,
)

internal fun TestScope.useCase(
    library: LibraryRepository,
    fetch: suspend (Manga) -> AppResult<MangaDetails> = { AppResult.Success(details(it)) },
) = RefreshAllLibraryChaptersUseCase(
    ObserveLibraryUseCase(library),
    FetchMangaDetailsUseCase(
        object : MangaDetailsRepository {
            override suspend fun fetchDetails(manga: Manga) = fetch(manga)
        },
    ),
    PersistNewChaptersAndNotifyUseCase(library),
    library,
    dispatchers(),
)

internal fun library(vararg titles: String) =
    FakeLibraryRepository().apply {
        emitLibrary(titles.map { sampleLibraryManga(title = it) })
    }

internal fun ch(n: String) = Chapter(n, n, "c/$n", null, false, false)

internal suspend fun pauseRefresh(
    reached: CompletableDeferred<Unit>,
    settled: CompletableDeferred<Unit>,
) {
    reached.complete(Unit)
    try {
        awaitCancellation()
    } finally {
        settled.complete(Unit)
    }
}
