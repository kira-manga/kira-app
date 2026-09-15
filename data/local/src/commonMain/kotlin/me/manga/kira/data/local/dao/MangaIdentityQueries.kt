package me.manga.kira.data.local.dao

import androidx.room.Query

/** Identity lookups inherited by [MangaDao]; lookup choice stays explicit at each call site. */
interface MangaIdentityQueries {
    @Query(
        """
      SELECT id
      FROM saved_manga
      WHERE api   = :api
        AND title = :title
      LIMIT 1
    """,
    )
    suspend fun getIdByApiAndTitle(
        api: String,
        title: String,
    ): Long?

    /** Resolve the exact saved api/URL parent, or null; title metadata is never a fallback. */
    @Query("SELECT id FROM saved_manga WHERE api = :api AND url = :mangaUrl LIMIT 1")
    suspend fun getIdByApiAndUrl(
        api: String,
        mangaUrl: String,
    ): Long?
}
