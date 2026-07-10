package me.manga.kira.sources.contracts.model

import me.manga.kira.domain.model.filters.FilterCondition
import me.manga.kira.domain.model.filters.FilterControlType
import me.manga.kira.domain.model.filters.FilterOption
import me.manga.kira.domain.model.filters.SourceFilter

/**
 * The catalog-metadata projection of one validated [SourceConfig] stanza — everything the app
 * needs to DISPLAY, ENABLE, and ROUTE a source, and nothing of the executable spec
 * (endpoints/fields/strategies stay engine-internal). This is the runtime model the rest of the
 * app consumes instead of the legacy `MangaSource` enum: `:data` joins it with the Room `sources`
 * row (user state) to build the domain models the UI renders.
 *
 * Derived exclusively from the validated active document via [toRuntimeDescriptor] — never from an
 * enum, a hardcoded api list, or per-source Kotlin. See docs/sources/MANGASOURCE_DECOUPLING_PLAN.md.
 */
data class RuntimeSourceDescriptor(
    /** Stable identity — the persisted key everywhere. Never rename a shipped api. */
    val api: String,
    /** User-visible name. Defaults to [api] in the config model, so it is always non-blank. */
    val displayName: String,
    /** Parenthesised language tag, e.g. `"(AR)"` — the persisted grouping key. */
    val language: String,
    /** `"generic"` | `"legacy"` | `"kotlin:<id>"`. */
    val engine: String,
    val baseUrl: String,
    /** Display ordering within a language group (lower first). */
    val priority: Int,
    /** First-seed enablement only — the user's toggle owns the Room row afterwards. */
    val enabledByDefault: Boolean,
    /** Operational status in config vocabulary (`"WORKING"`, `"UNDER_MAINTENANCE"`, …). */
    val siteState: String,
    /** `"active"` | `"disabled"` | `"removed"`. */
    val lifecycle: String,
    /** Key into the packaged-drawable icon registry, or null when the stanza ships none. */
    val iconResourceKey: String?,
    /** HTTPS icon URL fallback, or null. A packaged [iconResourceKey] always wins over this. */
    val iconRemoteUrl: String?,
    /** Genres the source suppresses — feeds the adult-content gate for generic sources. */
    val blacklistGenres: List<String>,
    /**
     * Static request headers the stanza declares (referer/UA/etc.) — what image loads inject for a
     * config-backed source, merged UNDER any captured per-api headers (engine semantics).
     */
    val headers: Map<String, String>,
    /**
     * Ordered advanced-filter projection for the Search UI (config-driven filters, 2026-07).
     * Display/selection model ONLY — request mapping stays engine-internal on [SourceConfig.filters],
     * preserving this descriptor's "display/route, never executable spec" contract. Empty → the
     * source exposes no advanced filters (plain search only).
     */
    val filters: List<SourceFilter> = emptyList(),
) {
    val isGeneric: Boolean get() = engine == ENGINE_GENERIC

    companion object {
        const val ENGINE_GENERIC = "generic"
    }
}

/** The single derivation point from the validated config stanza. */
fun SourceConfig.toRuntimeDescriptor(): RuntimeSourceDescriptor =
    RuntimeSourceDescriptor(
        api = api,
        displayName = displayName.ifBlank { api },
        language = language,
        engine = engine,
        baseUrl = baseUrl,
        priority = priority,
        enabledByDefault = enabled,
        siteState = siteState,
        lifecycle = lifecycle,
        iconResourceKey = icon?.resourceKey?.takeIf { it.isNotBlank() },
        iconRemoteUrl = icon?.remoteUrl?.takeIf { it.isNotBlank() },
        blacklistGenres = blacklistGenres,
        headers = headers,
        filters = filters.map { it.toSourceFilter() },
    )

/**
 * The single spec→display projection. The [FilterDefinition.request] block is intentionally NOT
 * projected — the engine reads it from the config; the UI never sees request mapping.
 */
fun FilterDefinition.toSourceFilter(): SourceFilter =
    SourceFilter(
        id = id,
        label = label,
        type =
            when (type) {
                "multiselect" -> FilterControlType.MULTISELECT
                "toggle" -> FilterControlType.TOGGLE
                "text" -> FilterControlType.TEXT
                "number" -> FilterControlType.NUMBER
                else -> FilterControlType.SELECT // validator guarantees the whitelist; "select" is the base case
            },
        options = options.map { FilterOption(value = it.value, label = it.label.ifBlank { it.value }) },
        defaultValues =
            when {
                defaults.isNotEmpty() -> defaults
                default.isNotBlank() -> listOf(default)
                else -> emptyList()
            },
        required = required,
        visibleWhen = visibleWhen.map { FilterCondition(filterId = it.filter, anyOf = it.anyOf) },
        excludeOf = excludeOf.takeIf { it.isNotBlank() },
    )
