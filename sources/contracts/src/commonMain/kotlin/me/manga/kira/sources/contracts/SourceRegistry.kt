package me.manga.kira.sources.contracts

import me.manga.kira.sources.contracts.model.RuntimeSourceDescriptor

/**
 * Lookup of generic sources by API key. [get] returns an executable client only for an active,
 * operationally working source; missing/non-active/non-generic/non-working APIs return null.
 * Implementations must never infer a legacy adapter.
 *
 * The registry is also the ONE public reader of catalog metadata: [descriptor]/[genericDescriptors]
 * project the validated active config document into [RuntimeSourceDescriptor]s so no caller ever
 * rebuilds a source list from an enum, a hardcoded api set, or per-source Kotlin
 * (docs/sources/MANGASOURCE_DECOUPLING_PLAN.md §2).
 */
interface SourceRegistry {
    fun get(api: String): MangaSourceClient?

    /** True when [api] is present with an active lifecycle in the accepted generic catalog. */
    fun isConfigBacked(api: String): Boolean

    /**
     * Active-lifecycle generic catalog metadata for [api], including a non-working operational
     * state that the UI must explain, or null when the source is absent/disabled.
     */
    fun descriptor(api: String): RuntimeSourceDescriptor?

    /** Ordered descriptors of every active generic stanza in the complete accepted catalog. */
    fun genericDescriptors(): List<RuntimeSourceDescriptor>
}
