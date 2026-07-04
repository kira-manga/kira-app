package me.manga.kira.domain.usecase.reader

import kotlinx.coroutines.flow.first
import me.manga.kira.domain.model.Chapter
import me.manga.kira.domain.model.Manga
import me.manga.kira.domain.repository.HistoryRepository
import me.manga.kira.domain.repository.SettingsRepository

/**
 * Record a reading-history row for a chapter the user just opened (Reader-convergence R3a) —
 * the verb the rework Reader was missing. Mirrors the legacy reader's "record on chapter open"
 * behaviour: legacy `HistoryViewModel.insertHistory(historyItem)` →
 * `HistoryRepository.insertHistory` → `HistoryDao.insertOrUpdateHistory` (upsert keyed by
 * `mangaUrl`), gated on the incognito flag.
 *
 * **Incognito gate (the one rule that lives here).** When incognito is ON the use case no-ops —
 * no history row is written, matching legacy semantics (the legacy reader gated the insert on
 * `settingsRepository.incognitoFlow`). The flag is NOT re-implemented: it is read through the narrow
 * [SettingsRepository.observeIncognito] accessor, which the `:data` impl delegates straight to the
 * legacy `incognitoFlow` (a single `DataStore` cell). This is the hot path — record-history fires on
 * every chapter open / Next / Prev — so it must NOT subscribe to the full `observeSettings()`
 * snapshot, whose first emission can't fire until the recursive cache-folder size walk completes.
 *
 * When incognito is OFF the call delegates straight to [HistoryRepository.record], which the
 * `:data` strangler-fig impl maps to the legacy `HistoryItemD` + forwards to the legacy facade.
 *
 * Contract §6 SRP: one rule — "gate the record on incognito, then delegate". The Manga+Chapter →
 * row mapping lives in `:data`; the flag-source plumbing lives in [SettingsRepository].
 *
 * Contract §6 DIP: constructor-injected [HistoryRepository] + [SettingsRepository] (both
 * `:domain`); the rework `ReaderViewModel` depends on this use case, never on the repositories
 * directly. Koin binds it in `readerReworkModule` as a `factory`.
 */
class RecordHistoryUseCase(
    private val repository: HistoryRepository,
    private val settings: SettingsRepository,
) {
    suspend operator fun invoke(manga: Manga, chapter: Chapter) {
        // Incognito read: no-op when ON (do not leave a reading trail), matching legacy.
        if (settings.observeIncognito().first()) return
        repository.record(manga, chapter)
    }
}
