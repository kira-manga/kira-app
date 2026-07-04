package me.manga.kira.domain.model.home

/**
 * Pure-domain representation of one source/language tab in the Home tab row.
 *
 * The legacy Home screen renders one tab per *enabled* source (driven by `RepoSettingsViewModel`'s
 * enabled-repos list); each tab carries its source [api] + display [language], an optional
 * [iconKey] the UI resolves to a source icon, and the per-source [siteState] used to gate
 * search/fetch and render a maintenance/stopped banner.
 *
 * [iconKey] is a framework-free string handle (e.g. the source name) rather than a drawable/
 * resource reference — icon resolution is a `:ui`/`:composeApp` concern (`RepoIconResolver`),
 * kept out of `:domain` per the layering contract. Null when the source ships no icon.
 */
data class SourceTab(
    /** Source API identifier — the tab's identity and the key passed to fetch/search. */
    val api: String,
    /** ISO-639-1 source language code used for the tab label/grouping. */
    val language: String,
    /** Opaque icon handle the UI resolves to a source icon; null when none. */
    val iconKey: String?,
    /** Current health/availability of the source backing this tab. */
    val siteState: SiteState,
    /**
     * The source's base/home URL — what Home's "open in WebView" opens (native parity:
     * `HomeViewModel.getCurrentBaseUrl()`). Defaulted empty for test/preview constructions; the
     * `:data` mapper supplies the real value from the source repo.
     */
    val baseUrl: String = "",
)

/**
 * Per-source health/availability state, gating whether a source can be searched/fetched and which
 * banner the Home screen shows.
 *
 * Mirrors the legacy `SourceState` enum (`:shared/.../repo_settings/domain/SourceState.kt`)
 * value-for-value — the four constants persist as name strings in existing Room rows, so the
 * names are reproduced verbatim:
 *  - [WORKING] — healthy source, available for search/fetch.
 *  - [UNDER_MAINTENANCE] — temporary backend-side outage; Home shows a maintenance banner.
 *  - [STOPPED] — permanent decommission.
 *  - [ADULT_18_PLUS] — adult-content gate (legacy `ADULT_18_PLUS`).
 */
enum class SiteState {
    WORKING,
    UNDER_MAINTENANCE,
    STOPPED,
    ADULT_18_PLUS,
}
