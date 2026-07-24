package me.manga.kira.sources.runtime

import me.manga.kira.data.local.dao.SourcesDao
import me.manga.kira.sources.contracts.SourceBaseUrlProvider

/**
 * Resolves a source's live base URL from the active catalog projection in Room. A null or blank row
 * leaves the immutable descriptor's base URL in effect.
 */
class DbSourceBaseUrlProvider(
    private val sourcesDao: SourcesDao,
) : SourceBaseUrlProvider {
    override suspend fun baseUrlFor(api: String): String? = sourcesDao.getBaseUrlFor(api)
}
