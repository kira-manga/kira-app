package me.manga.kira.sources.contracts

import me.manga.kira.sources.contracts.model.RuntimeSourceDescriptor

/**
 * Lookup of active generic sources by API key. A missing/non-active/non-generic API returns null;
 * implementations must never infer a legacy adapter.
 *
 * The registry is also the ONE public reader of catalog metadata: [descriptor]/[genericDescriptors]
 * project the validated active config document into [RuntimeSourceDescriptor]s so no caller ever
 * rebuilds a source list from an enum, a hardcoded api set, or per-source Kotlin
 * (docs/sources/MANGASOURCE_DECOUPLING_PLAN.md §2).
 */
interface SourceRegistry {
    fun get(api: String): MangaSourceClient?

    /**
     * True only when [api] is active in the complete accepted generic catalog.
     */
    fun isConfigBacked(api: String): Boolean

    /**
     * Active generic catalog metadata for [api], or null when the source is unavailable.
     */
    fun descriptor(api: String): RuntimeSourceDescriptor?

    /** Ordered descriptors of every active generic stanza in the complete accepted catalog. */
    fun genericDescriptors(): List<RuntimeSourceDescriptor>
}
