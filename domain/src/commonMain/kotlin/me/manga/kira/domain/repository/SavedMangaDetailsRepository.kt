package me.manga.kira.domain.repository

import kotlinx.coroutines.flow.Flow
import me.manga.kira.core.result.AppResult
import me.manga.kira.domain.model.identity.WorkLocator
import me.manga.kira.domain.model.library.SavedWorkDetails

/**
 * Local/offline Details projection. Parent resolution and chapter reads use one accepted-policy
 * transaction; every saved payload carries its retained owner. Unrelated same-title rows never match.
 */
interface SavedMangaDetailsRepository {
    /** Re-emits on parent/chapter writes and accepted policy changes; conflicts are explicit failures. */
    fun observeSavedDetails(work: WorkLocator): Flow<AppResult<SavedWorkDetails?>>
}
