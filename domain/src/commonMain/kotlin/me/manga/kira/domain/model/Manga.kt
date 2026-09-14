package me.manga.kira.domain.model

/**
 * Pure-domain representation of a manga.
 *
 * Contract §4 forbids framework annotations on domain entities. This type has no `@Serializable`,
 * no Room annotations, no `@Parcelize`. DTOs (network/disk) and UI models live in their own
 * layers and map to/from this type at the boundary.
 *
 * Saved manga have a surrogate ID and a unique exact [url]. Persisted chapter actions use that
 * parent URL together with the chapter URL. [api], [language] and [title] remain metadata and
 * feature-specific lookup inputs, not the database primary key. This does not migrate settings
 * or backup identity contracts.
 *
 * **Audit-trail postscript** (Phase 9.x.cluster135.staleKdocSweep.cascade,
 * Task #591, 2026-05-28): classified as follows after recursive symbol
 * verification (one-hundred-and-eighteenth sibling of the cluster57-134
 * sweep — first file of the wave-24 third-cluster `:domain/model/root/`
 * 3-leaf-model batch alongside Chapter plus MangaDetails; opens
 * cluster135):
 *  (a) "Contract-§4-forbids-framework-annotations-on-domain-entities +
 *  no-@Serializable + no-Room-annotations + no-@Parcelize + DTOs-network-
 *  disk-and-UI-models-live-in-their-own-layers-and-map-to-from-this-
 *  type-at-the-boundary" — LIVE-NOT-STALE plus FULFILLED-PREDICTION.
 *  Verified: zero framework annotations on the data class (no import
 *  statements at all in Manga.kt). The DTO mappers live at the boundary
 *  in :data — LibraryRepositoryImpl.kt L197 declares `private fun Manga.
 *  toNewEntity(): SavedMangaEntity` which translates between the
 *  framework-free :domain type and Room's @Entity-annotated
 *  SavedMangaEntity at the persistence boundary. UI mappers (e.g.
 *  LibraryCardUiModel binding) consume Manga directly as a read-only
 *  domain value.
 *  (b) The historical composite-primary-key account is superseded by the current persistence
 *  contract above: `SavedMangaEntity` has a surrogate ID and `UNIQUE(url)`.
 */
data class Manga(
    /** Source API identifier (e.g. "MangaPlus", "MangaDex", per-language slug). */
    val api: String,
    /** ISO-639-1 source language code ("en", "ar", …). */
    val language: String,
    /** Manga title as published by the source. */
    val title: String,
    /** Source detail-page URL — the canonical address for re-fetching. */
    val url: String,
    /** Cover image URL — empty string when the source doesn't ship one. */
    val coverUrl: String,
    /** Source-supplied rating; null when the source doesn't expose one. */
    val rating: Int?,
    /** Source, saved or imported genre labels; no required lowercasing or trimming. May be empty. */
    val genres: List<String>,
)
