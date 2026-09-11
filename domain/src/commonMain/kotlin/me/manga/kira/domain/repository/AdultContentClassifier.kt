package me.manga.kira.domain.repository

/**
 * Classifies genre labels using the currently active, verified source descriptor's blacklist.
 *
 * This synchronous in-memory policy never consults a legacy fallback or a global genre list.
 * Consumers depend on this port rather than on the source registry or its implementation.
 */
interface AdultContentClassifier {
    /**
     * Returns `true` when any of [genres] contains a blacklist entry for [api], ignoring case.
     *
     * Uses Kotlin's case-insensitive substring comparison on the supplied strings: no trimming,
     * word boundaries, locale-specific rules or Unicode normalization. Missing sources, an empty
     * genre list or an empty blacklist return `false`. An empty blacklist entry matches any
     * supplied genre string.
     */
    fun isAdultContent(
        api: String,
        genres: List<String>,
    ): Boolean
}
