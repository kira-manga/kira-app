package me.manga.kira.sources.contracts

/**
 * Type-agnostic lookup of sources by API key. The concrete registry (assembled at the composition
 * root) decides per source whether to return a config-driven engine client or a legacy adapter —
 * the caller cannot tell. This is the seam that lets a source be migrated from Kotlin to config
 * without any change above `:data`.
 */
interface SourceRegistry {
    fun get(api: String): MangaSourceClient?

    fun availableApis(): List<String>

    /**
     * True if [api] is served by the config-driven engine (it has a valid `generic` config), i.e.
     * [get] returns the generic client rather than a plain legacy adapter. Callers that keep a separate
     * legacy path (e.g. `:data`) use this to route ONLY config-backed sources through the registry while
     * everything else stays on the legacy path unchanged. (Config-backed sources are generic-ONLY — see
     * the registry impl; there is no legacy fallback for them.)
     */
    fun isConfigBacked(api: String): Boolean
}
