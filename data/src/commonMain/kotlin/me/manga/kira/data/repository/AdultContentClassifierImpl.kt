package me.manga.kira.data.repository

import me.manga.kira.domain.repository.AdultContentClassifier
import me.manga.kira.sources.contracts.SourceRegistry

/**
 * Applies the blacklist declared by the currently active, verified source descriptor.
 *
 * Missing catalog entries have no metadata authority and are treated as unavailable elsewhere;
 * this classifier never consults a compiled legacy repository.
 */
class AdultContentClassifierImpl(
    private val sourceRegistry: SourceRegistry,
) : AdultContentClassifier {

    override fun isAdultContent(api: String, genres: List<String>): Boolean {
        if (genres.isEmpty()) return false
        val blacklist = sourceRegistry.descriptor(api)?.blacklistGenres.orEmpty()
        return blacklist.isNotEmpty() && genres.any(blacklist::contains)
    }
}
