package me.manga.kira.domain.repository

import kotlinx.coroutines.flow.Flow
import me.manga.kira.domain.model.MangaDetails

/**
 * Reactive read of a *saved* manga's details straight from the local store (Room), restoring the
 * legacy `LibraryMangaDetails` offline path the rework dropped.
 *
 * Why this exists (regression fix, 2026-05-31): the rework collapsed every Details entry onto the
 * single network-fetch path ([MangaDetailsRepository.fetchDetails]). Opening a manga from the
 * Library therefore showed a spinner, then a *fresh* network chapter list with the user's read /
 * downloaded / bookmark marks gone — and nothing at all when offline or when the source failed
 * ("failed to load"). This repository emits the saved chapter list (with persisted read-state)
 * immediately so the Details ViewModel can render it before — and independently of — any network
 * refresh.
 *
 * Identity is the legacy `(api, title)` pair, the key `MangaDao.getIdByApiAndTitle` resolves to a
 * Room `mangaId`; the emission updates reactively as the user reads/downloads/bookmarks chapters.
 */
interface SavedMangaDetailsRepository {
    /**
     * Observe the saved [MangaDetails] for `(api, title)`. Emits `null` when the manga is not in
     * the library (no Room row), otherwise a [MangaDetails] whose chapters carry the locally
     * persisted `isRead` / `isDownloaded` / `isBookmarked` flags, re-emitting on every local write.
     */
    fun observeSavedDetails(api: String, title: String): Flow<MangaDetails?>
}
