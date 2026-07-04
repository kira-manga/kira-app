package me.manga.kira.domain.model

/**
 * Pure-domain representation of a manga's full details — the model the Details screen consumes.
 *
 * Mirrors the legacy `MangaInfo` (see `:shared/.../domain/model/MangaInfo.kt`) **minus** framework
 * annotations and the `MutableList` mutability:
 *  - No `@Serializable` (contract §4).
 *  - `MutableList<ChapterItem>` → `List<Chapter>` — immutability is a rework invariant; mutations
 *    happen at the data-layer boundary, not on the domain model.
 *
 * Field-level rationale:
 *  - [api] / [language] / [title] / [url] / [coverUrl] mirror the [Manga] identity + cover fields
 *    (same wire-format compatibility — see `Manga.kt` KDoc).
 *  - [rating] / [status] are kept as `String` rather than typed values because sources hand them
 *    back in heterogeneous formats ("4.8 / 5", "Ongoing") and the UI just renders them. Premature
 *    typing here would force every source-mapper to parse-and-reformat, losing source-specific
 *    nuance.
 *  - [description] / [author] are free-form text from the source.
 *  - [genres] is a pre-split string list — the source-mappers do the splitting.
 *  - [chapters] is the chapter list ordered as the source ships it (typically newest-first; the
 *    presentation layer reorders if needed).
 *
 * Identity is by [api] + [language] + [title] — same composite key as [Manga] (and the legacy
 * `SavedMangaEntity` primary key) so a [MangaDetails] always corresponds to exactly one [Manga].
 *
 * Phase 9.y.mangainfo.fieldprune.cumulative (Task #417): dropped 6 orphan fields after a 3-pass
 * accessor-read audit found zero reach on the rework `DetailsScreen` / `DetailsViewModel` /
 * `DetailsState` / `MangaDetailsMappers` round-trip, and zero reach on every legacy consumer of
 * the upstream `MangaInfo` it strangler-figs over:
 *   - `artist: String`         — zero `details.artist` reads; legacy `DetailsContent` /
 *                                `HeaderSection` render only `author`.
 *   - `ratingCount: String`    — zero `details.ratingCount` reads anywhere.
 *   - `favoritesCount: String` — zero `details.favoritesCount` reads anywhere.
 *   - `otherNames: String`     — zero `details.otherNames` reads anywhere.
 *   - `yearOfProduction: String` — zero reads; never rendered on either Details path.
 *   - `tags: List<String>`     — zero `details.tags` reads; the rework `DetailsScreen` only reads
 *                                `details.genres`.
 * The matching prune lands on `:shared`'s legacy [MangaInfo] in stages (default-values prep + per-
 * language source-repo writer cleanup + final field-drop). See `MangaDetailsMappers.kt`
 * `LegacyMangaInfo.toDomain()` — the 6-field copy lines were dropped in the same prep commit.
 *
 * **Audit-trail postscript** (Phase 9.x.cluster135.staleKdocSweep.cascade,
 * Task #591, 2026-05-28): classified as follows after recursive symbol
 * verification (one-hundred-and-twentieth sibling of the cluster57-134
 * sweep — third and closing file of the wave-24 third-cluster `:domain/
 * model/root/` 3-leaf-model batch alongside Manga plus Chapter; closes
 * cluster135):
 *  (a) "Mirrors-the-legacy-MangaInfo-minus-framework-annotations-and-
 *  the-MutableList-mutability + No-@Serializable-contract-§4 +
 *  MutableList-ChapterItem-arrow-List-Chapter + immutability-is-a-
 *  rework-invariant + mutations-happen-at-the-data-layer-boundary-
 *  not-on-the-domain-model" — LIVE-NOT-STALE plus FULFILLED-PREDICTION.
 *  Verified: zero framework annotations on the data class; no imports
 *  at all in MangaDetails.kt. Every field type is either a Kotlin
 *  stdlib primitive or the framework-free Chapter sibling. Mutations
 *  occur only in the :data mapper layer (MangaDetailsMappers.kt) — the
 *  :domain model is structurally immutable per the rework convention.
 *  (b) "Field-level-rationale + api-plus-language-plus-title-plus-url-
 *  plus-coverUrl-mirror-the-Manga-identity-plus-cover-fields-(same-
 *  wire-format-compatibility) + rating-status-kept-as-String-rather-
 *  than-typed-values-because-sources-hand-them-back-in-heterogeneous-
 *  formats-(4.8-of-5-Ongoing) + Premature-typing-here-would-force-
 *  every-source-mapper-to-parse-and-reformat-losing-source-specific-
 *  nuance + description-author-are-free-form-text-from-the-source +
 *  genres-is-a-pre-split-string-list + chapters-is-the-chapter-list-
 *  ordered-as-the-source-ships-it-(typically-newest-first-the-
 *  presentation-layer-reorders-if-needed) + Identity-is-by-api-plus-
 *  language-plus-title-same-composite-key-as-Manga-(and-the-legacy-
 *  SavedMangaEntity-primary-key)-so-a-MangaDetails-always-corresponds-
 *  to-exactly-one-Manga" — LIVE-NOT-STALE plus FULFILLED-PREDICTION.
 *  Verified: the field set is preserved verbatim across the rework;
 *  rating plus status remain String-typed per source-heterogeneity
 *  rationale (no source-mapper parses-and-reformats); the composite
 *  (api+language+title) identity matches the Manga sibling exactly so
 *  the 1:1 correspondence holds (a MangaDetails for (api, language,
 *  title) maps unambiguously to the Manga for the same triple in the
 *  Library).
 *  (c) "Phase-9.y.mangainfo.fieldprune.cumulative-(Task-#417)-dropped-
 *  6-orphan-fields-after-a-3-pass-accessor-read-audit-found-zero-reach-
 *  on-the-rework-DetailsScreen-DetailsViewModel-DetailsState-
 *  MangaDetailsMappers-round-trip + artist-ratingCount-favoritesCount-
 *  otherNames-yearOfProduction-tags-all-zero-reads + matching-prune-
 *  lands-on-:shared's-legacy-MangaInfo-in-stages-(default-values-prep-
 *  plus-per-language-source-repo-writer-cleanup-plus-final-field-drop)
 *  + MangaDetailsMappers-LegacyMangaInfo.toDomain-the-6-field-copy-
 *  lines-were-dropped-in-the-same-prep-commit" — LIVE-NOT-STALE plus
 *  FULFILLED-PREDICTION. Verified via recursive grep: the rework
 *  MangaDetails data class carries exactly 11 fields (api, language,
 *  title, url, coverUrl, description, author, rating, status, genres,
 *  chapters) — the 6 pruned fields are gone and zero downstream
 *  consumer references them. MangaDetailsMappers.kt L60 `internal fun
 *  LegacyMangaInfo.toDomain(): MangaDetails` carries no `artist`,
 *  `ratingCount`, `favoritesCount`, `otherNames`, `yearOfProduction`,
 *  or `tags` copy line. The Task #417 prune held across the
 *  strangler-fig transition without regression.
 *  Three classifications STAND on their own merits. Closes cluster135.
 *  Original Phase 6.2.x-era prose preserved verbatim per the audit-
 *  trail-preservation convention.
 */
data class MangaDetails(
    val api: String,
    val language: String,
    val title: String,
    val url: String,
    val coverUrl: String,
    val description: String,
    val author: String,
    val rating: String,
    val status: String,
    val genres: List<String>,
    val chapters: List<Chapter>,
)
