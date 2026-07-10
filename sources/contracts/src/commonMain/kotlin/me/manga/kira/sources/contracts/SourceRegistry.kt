package me.manga.kira.sources.contracts

import me.manga.kira.sources.contracts.model.RuntimeSourceDescriptor

/**
 * Type-agnostic lookup of sources by API key. The concrete registry (assembled at the composition
 * root) decides per source whether to return a config-driven engine client or a legacy adapter —
 * the caller cannot tell. This is the seam that lets a source be migrated from Kotlin to config
 * without any change above `:data`.
 *
 * The registry is also the ONE public reader of catalog metadata: [descriptor]/[genericDescriptors]
 * project the validated active config document into [RuntimeSourceDescriptor]s so no caller ever
 * rebuilds a source list from an enum, a hardcoded api set, or per-source Kotlin
 * (docs/sources/MANGASOURCE_DECOUPLING_PLAN.md §2).
 */
interface SourceRegistry {
    fun get(api: String): MangaSourceClient?

    /**
     * True if [api] is served by the config-driven engine (it has a valid `generic` config), i.e.
     * [get] returns the generic client rather than a plain legacy adapter. Callers that keep a separate
     * legacy path (e.g. `:data`) use this to route ONLY config-backed sources through the registry while
     * everything else stays on the legacy path unchanged. (Config-backed sources are generic-ONLY — see
     * the registry impl; there is no legacy fallback for them.)
     */
    fun isConfigBacked(api: String): Boolean

    /**
     * Catalog metadata for [api] from the validated active document — any engine, including the
     * metadata-only `legacy` stanzas. Null for an api with no stanza (unknown or pre-config data).
     */
    fun descriptor(api: String): RuntimeSourceDescriptor?

    /** Descriptors of every `engine == "generic"` stanza in the validated active document. */
    fun genericDescriptors(): List<RuntimeSourceDescriptor>
}
