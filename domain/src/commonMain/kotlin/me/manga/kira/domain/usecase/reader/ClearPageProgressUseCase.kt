package me.manga.kira.domain.usecase.reader

import me.manga.kira.domain.repository.PageProgressRepository

/**
 * Prunes the per-page download/decode progress entry for [pageUrl] from the process-singleton
 * progress map.
 *
 * DIP seam (contract §6): the Reader VM depends on this use case, never on
 * [PageProgressRepository] directly — mirroring [ObservePageProgressUseCase]. Called from the
 * Reader VM's `onCleared` for each page URL of the chapters it had loaded, so the in-memory map
 * does not grow without bound across long sessions (the lifecycle the [PageProgressRepository]
 * KDoc documents). No-op if no entry exists.
 */
class ClearPageProgressUseCase(
    private val repository: PageProgressRepository,
) {
    operator fun invoke(pageUrl: String) {
        repository.clear(pageUrl)
    }
}
