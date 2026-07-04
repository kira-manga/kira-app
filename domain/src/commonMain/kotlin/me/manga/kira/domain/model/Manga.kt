package me.manga.kira.domain.model

/**
 * Pure-domain representation of a manga.
 *
 * Contract §4 forbids framework annotations on domain entities. This type has no `@Serializable`,
 * no Room annotations, no `@Parcelize`. DTOs (network/disk) and UI models live in their own
 * layers and map to/from this type at the boundary.
 *
 * Identity is by [api] + [language] + [title] which mirrors the legacy primary-key composition
 * used in `SavedMangaEntity` — preserves wire-format compatibility for existing user libraries
 * (baseline §8 / contract functionality-preservation gate).
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
 *  (b) "Identity-is-by-api-plus-language-plus-title-which-mirrors-the-
 *  legacy-primary-key-composition-used-in-SavedMangaEntity + preserves-
 *  wire-format-compatibility-for-existing-user-libraries-(baseline-§8-
 *  contract-functionality-preservation-gate)" — LIVE-NOT-STALE plus
 *  FULFILLED-PREDICTION. Verified via recursive grep: LibraryRepository-
 *  Impl.kt L197 `Manga.toNewEntity()` builds SavedMangaEntity with `api
 *  = api`, `language = language`, `title = title` filling the legacy 3-
 *  field composite PK; LibraryRepository.observeIsInLibrary plus
 *  ToggleInLibraryUseCase plus GetMangaByIdUseCase plus 25+ consumers
 *  all key on the same (api+language+title) triple. Existing user
 *  preferences survive the strangler-fig transition without resetting.
 *  Two classifications STAND on their own merits. Opens cluster135.
 *  Original Phase 6.2.x-era prose preserved verbatim per the audit-
 *  trail-preservation convention.
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
    /** Genre tags as the source labels them; lower-cased + trimmed. May be empty. */
    val genres: List<String>,
)
