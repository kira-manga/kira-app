package me.manga.kira.domain.usecase.reader

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import me.manga.kira.domain.model.Chapter
import me.manga.kira.domain.model.Manga
import me.manga.kira.domain.model.settings.CbzConversionProgress
import me.manga.kira.domain.model.settings.SettingsSnapshot
import me.manga.kira.domain.model.settings.SettingsToggle
import me.manga.kira.domain.repository.HistoryRepository
import me.manga.kira.domain.repository.SettingsRepository
import me.manga.kira.domain.model.history.HistoryEntry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Contract tests for [RecordHistoryUseCase] (Reader-convergence R3a).
 *
 * Pins the incognito gate: records when incognito is OFF, no-ops when ON. The Manga+Chapter →
 * `HistoryItemD` mapping + the strangler-fig forward to the legacy facade live in the `:data`
 * impl (`HistoryRepositoryImpl.record`) and are out of scope for this pure-`:domain` test. The
 * incognito flag is seamed via a fake [SettingsRepository]'s narrow `observeIncognito()` accessor
 * (no flag logic is duplicated — the test exercises the same read path production uses).
 */
class RecordHistoryUseCaseTest {

    private class FakeHistoryRepository : HistoryRepository {
        val recorded = mutableListOf<Pair<Manga, Chapter>>()
        override fun observeHistory(): Flow<List<HistoryEntry>> = MutableStateFlow(emptyList())
        override suspend fun deleteEntry(entry: HistoryEntry) = Unit
        override suspend fun deleteAll() = Unit
        override suspend fun record(manga: Manga, chapter: Chapter) {
            recorded += manga to chapter
        }
    }

    /** Seams the incognito flag without duplicating its logic — drives the narrow observeIncognito(). */
    private class FakeSettingsRepository(private val incognito: Boolean) : SettingsRepository {
        private val snapshot = MutableStateFlow(
            SettingsSnapshot(
                downloadedOnly = false,
                incognito = incognito,
                followSystemTheme = true,
                darkMode = false,
                pureBlack = true,
                cacheSizeBytes = 0L,
                useCbzFormat = true,
                autoConvertToCbz = false,
                allowCompressionInLowPower = false,
            ),
        )
        override fun observeSettings(): Flow<SettingsSnapshot> = snapshot
        override fun observeIncognito(): Flow<Boolean> = MutableStateFlow(incognito)
        override suspend fun setToggle(toggle: SettingsToggle, value: Boolean): Result<Unit> =
            Result.success(Unit)
        override suspend fun clearLargeCache(): Result<Unit> = Result.success(Unit)
        override suspend fun compressExistingDownloads(): Result<Unit> = Result.success(Unit)
        override fun observeCbzConversion(): Flow<CbzConversionProgress> =
            MutableStateFlow(CbzConversionProgress())
        override fun stopConversion() = Unit
        override fun clearConversionProgress() = Unit
    }

    private val manga = Manga(
        api = "MangaDex",
        language = "en",
        title = "Sample",
        url = "https://src/manga/1",
        coverUrl = "https://src/cover/1.jpg",
        rating = null,
        genres = emptyList(),
    )
    private val chapter = Chapter(
        number = "12",
        name = "Twelfth",
        url = "https://src/ch/12",
        date = null,
        isDownloaded = false,
        isBookmarked = false,
    )

    @Test
    fun records_when_not_incognito() = runTest {
        val repo = FakeHistoryRepository()
        val useCase = RecordHistoryUseCase(
            repository = repo,
            settings = FakeSettingsRepository(incognito = false),
        )

        useCase(manga, chapter)

        assertEquals(listOf(manga to chapter), repo.recorded)
    }

    @Test
    fun no_ops_when_incognito() = runTest {
        val repo = FakeHistoryRepository()
        val useCase = RecordHistoryUseCase(
            repository = repo,
            settings = FakeSettingsRepository(incognito = true),
        )

        useCase(manga, chapter)

        assertTrue(repo.recorded.isEmpty())
    }
}
