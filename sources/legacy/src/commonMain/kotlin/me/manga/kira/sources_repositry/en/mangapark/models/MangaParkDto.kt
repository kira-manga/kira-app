package me.manga.kira.sources_repositry.en.mangapark.models

// File: models/MangaParkModels.kt

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// GraphQL Request Models
@Serializable
data class GraphQL<T>(
    private val variables: T,
    private val query: String,
)

@Serializable
data class SearchVariables(
    private val select: SearchPayload
)

@Serializable
data class SearchPayload(
    @SerialName("word") private val query: String? = null,
    private val incGenres: List<String>? = null,
    private val excGenres: List<String>? = null,
    private val incTLangs: List<String>? = null,
    private val incOLangs: List<String>? = null,
    private val sortby: String? = null,
    private val chapCount: String? = null,
    private val origStatus: String? = null,
    private val siteStatus: String? = null,
    private val page: Int,
    private val size: Int,
)

@Serializable
data class IdVariables(
    private val id: String
)

// Response Models
@Serializable
data class Data<T>(val data: T)

@Serializable
data class Items<T>(val items: List<T>)

// Search Response Models
typealias SearchResponse = Data<SearchComics>
typealias DetailsResponse = Data<ComicNode>
typealias ChapterListResponse = Data<ChapterList>
typealias PageListResponse = Data<ChapterPages>

@Serializable
data class SearchComics(
    @SerialName("get_searchComic") val searchComics: Items<Data<MangaParkManga>>,
)

@Serializable
data class ComicNode(
    @SerialName("get_comicNode") val comic: Data<MangaParkManga>,
)

@Serializable
data class MangaParkManga(
    val id: String,
    val name: String?,
    val altNames: List<String>? = null,
    val authors: List<String>? = null,
    val artists: List<String>? = null,
    val genres: List<String>? = null,
    val originalStatus: String? = null,
    val uploadStatus: String? = null,
    val summary: String? = null,
    val extraInfo: String? = null,
    @SerialName("urlCoverOri") val cover: String? = null,
    val urlPath: String,
    @SerialName("max_chapterNode") val latestChapter: Data<ImageFiles>? = null,
    @SerialName("first_chapterNode") val firstChapter: Data<ImageFiles>? = null,
)

@Serializable
data class ChapterList(
    @SerialName("get_comicChapterList") val chapterList: List<Data<MangaParkChapter>>,
)

@Serializable
data class MangaParkChapter(
    val id: String,
    @SerialName("dname") val displayName: String? = null,
    val title: String? = null,
    val dateCreate: Long? = null,
    val dateModify: Long? = null,
    val urlPath: String,
    val srcTitle: String? = null,
    val userNode: Data<Name>? = null,
    val dupChapters: List<Data<MangaParkChapter>> = emptyList(),
)

@Serializable
data class Name(val name: String)

@Serializable
data class ChapterPages(
    @SerialName("get_chapterNode") val chapterPages: Data<ImageFiles>,
)

@Serializable
data class ImageFiles(
    val imageFile: UrlList,
)

@Serializable
data class UrlList(
    val urlList: List<String>,
)

/*
 * Audit-trail postscript (Phase 9.x.cluster197.staleKdocSweep.cascade, Task #652, 2026-05-29)
 * --------------------------------------------------------------------------------------------
 * Cluster197 leaf 4/4 — closing leaf, sibling 344. Closes cluster197's 4-leaf parser+models
 * batch AND closes the :en/ Repository tier (excluding the comick_io/models small-file subtree)
 * FULLY SWEPT. Cumulative §253-postscript count after cluster197 lands: 69.
 *
 * This file does NOT carry a Phase 7.2 migration preamble — pure-data DTOs with no behaviour
 * to port. Header comment at line 3 says "// File: models/MangaParkModels.kt" — see
 * FACTUALLY-DRIFTED-IN-PROSE-ONLY entry below.
 *
 * Body-level deltas (cluster57+ taxonomy):
 *
 *   • FACTUALLY-DRIFTED-IN-PROSE-ONLY — Header comment at line 3: `// File: models/MangaParkModels.kt`.
 *     The actual filename is `MangaParkDto.kt`, NOT `MangaParkModels.kt`. Double-axis prose
 *     drift: (a) plural-vs-singular ("Models" vs the singular subject), (b) suffix-naming
 *     ("Models" vs "Dto"). Code is correct; only the prose mislabels the file. Likely an
 *     artifact of a pre-port refactor where the file was renamed from `MangaParkModels.kt` →
 *     `MangaParkDto.kt` but the in-file comment wasn't synchronized. Preserved per §253 —
 *     comment is informational, no behavioural impact.
 *
 *   • LIVE-NOT-STALE — `GraphQL<T>` request envelope at lines 9-13 with `private val variables: T`
 *     and `private val query: String`. Pure wire-format DTO; private vals enforce write-only
 *     semantics (caller constructs, serializer reads via reflection). Same architectural
 *     choice as TapasticModels sibling 343's request DTOs.
 *
 *   • LIVE-NOT-STALE — `SearchVariables`/`SearchPayload`/`IdVariables` at lines 15-38: the
 *     two `Variables<T>` shapes consumed by MangaParkRepository sibling 337's 4 GraphQL
 *     query constants (SEARCH/DETAILS/CHAPTERS/PAGES per cluster196 leaf 2/5 postscript).
 *     Search uses `SearchPayload` (11-field rich filter); detail/chapters/pages use
 *     `IdVariables` (single id). Schema models the upstream GraphQL contract faithfully.
 *
 *   • LIVE-NOT-STALE — Type-alias collapse at lines 48-51:
 *       typealias SearchResponse  = Data<SearchComics>
 *       typealias DetailsResponse = Data<ComicNode>
 *       typealias ChapterListResponse = Data<ChapterList>
 *       typealias PageListResponse = Data<ChapterPages>
 *     Reduces caller boilerplate for the nested `Data<X>` GraphQL envelope wrapper. Caller-
 *     facing names match the 4 GraphQL queries. Clean Kotlin idiom.
 *
 *   • LIVE-NOT-STALE — `MangaParkManga.dupChapters: List<Data<MangaParkChapter>>` at line 96
 *     is SELF-REFERENTIAL via the `Data<T>` wrapper. Used to represent grouped duplicate
 *     chapters from different scanlation groups in MangaPark's data model — same chapter
 *     number, different uploaders. Wrapped in `Data<X>` because the GraphQL response keeps
 *     the same envelope shape at the nested level. Recursion is bounded by GraphQL response
 *     depth (typically 0-1 levels), not arbitrarily deep.
 *
 *   • DEBT-NOT-STALE — `MangaParkChapter.dateCreate: Long? = null` (line 91) +
 *     `dateModify: Long? = null` (line 92). Both Unix-epoch-millis timestamps. MangaParkRepository
 *     sibling 337 reads `dateCreate` via `Instant.fromEpochMilliseconds` (per its §253
 *     postscript). `dateModify` consumer status unverified at cluster197 boundary — if
 *     unreferenced, candidate for cluster197.fieldprune.cascade companion task. Preserved
 *     pending caller-survey.
 *
 *   • LIVE-NOT-STALE — `urlCoverOri`/`max_chapterNode`/`first_chapterNode` `@SerialName` overrides
 *     at lines 75/77/78. MangaPark GraphQL uses snake_case + underscore-suffix conventions;
 *     Kotlin layer uses camelCase + descriptive-name conventions. Mapping is the only point
 *     of contact, same as TapasticModels sibling 343.
 *
 *   • POTENTIAL-BUG-PRESERVED — `MangaParkManga.urlPath: String` declared NON-NULLABLE at
 *     line 76 while other String fields default to null (name, summary, cover, etc). If the
 *     upstream ever returns a manga without urlPath, deserialization throws
 *     `MissingFieldException`. Site contract presumably guarantees `urlPath` non-null —
 *     it's the canonical reader URL. Same non-null contract on `MangaParkChapter.urlPath`
 *     line 93 + `MangaParkChapter.id` line 88. Preserved per §253 — schema reflects upstream
 *     guarantee.
 *
 *   • LIVE-NOT-STALE — `Name(val name: String)` at line 100: minimal one-field wrapper for
 *     `userNode: Data<Name>?` at line 95. GraphQL response shape carries user information
 *     in a nested Data<Name> envelope; the wrapper exists solely to model that nesting.
 *     Could be inlined to `userNode: Data<{val name: String}>?` via anonymous-shape syntax
 *     if Kotlin supported it; `data class Name` is the conventional alternative.
 *
 *   • LIVE-NOT-STALE — `Items<T>` at line 45 + `Data<T>` at line 42: paired generic envelopes
 *     for the GraphQL response shape `{data: {get_searchComic: {items: [...]}}}`. Generic
 *     declaration lets the same envelope wrap any payload type — `SearchComics`, `ComicNode`,
 *     `ChapterList`, `ChapterPages`. Reuse across 4 query response shapes.
 *
 * Cross-cluster pattern register (cluster196 + cluster197 9-leaf arc, sibling indices 336-344):
 *
 *   • `:contentReference[oaicite:N]{index=N}` ChatGPT-paste artifact distribution:
 *       cluster196 leaf 1/5 (BatotoEn): 0
 *       cluster196 leaf 2/5 (MangaPark): 0
 *       cluster196 leaf 3/5 (Zazamanga): 1 (per its postscript)
 *       cluster196 leaf 4/5 (Batcave): 0
 *       cluster196 leaf 5/5 (Comick): 0
 *       cluster197 leaf 1/4 (MangaBuddyParser): 3
 *       cluster197 leaf 2/4 (ManhwatopParser): 14 (HEAVIEST)
 *       cluster197 leaf 3/4 (TapasticModels): 0
 *       cluster197 leaf 4/4 (MangaParkDto): 0
 *     Total cluster196+197: 18 ChatGPT-paste artifacts. ManhwatopParser sibling 342 alone
 *     contributes 14 — that file was the most direct LLM-paste port.
 *
 *   • Disabled blacklist filter outcomes across cluster197 parser-helpers:
 *       MangaBuddyParser sibling 341: blacklist set has 1 unmatchable string "mmmmmm" → no-op
 *       ManhwatopParser sibling 342: blacklist set is empty after commenting → no-op
 *     Both parser-helpers ship disabled genre filtering — different mechanism, same outcome.
 *     Contrasts with cluster196 Repository tier where blacklist sets are populated and active.
 *
 *   • Schema-conformance shims:
 *       MangaBuddyParser sibling 341: rating.toFloatOrNull().toInt() loses precision
 *       ManhwatopParser sibling 342: authors.toString() produces bracket-wrapped output
 *       TapasticModels sibling 343: lock emoji "🔒 " baked into chapter title
 *       MangaParkDto sibling 344: non-nullable urlPath under nullable-everything else
 *     Four distinct preserved schema-conformance peculiarities across the parser+models arc.
 *
 *   • Standalone-class architectural posture:
 *       MangaBuddyParser sibling 341: no @Inject, no base — pure helper
 *       ManhwatopParser sibling 342: no @Inject, no base — pure helper
 *       TapasticModels sibling 343: pure DTOs + extensions, no classes-with-methods
 *       MangaParkDto sibling 344: pure DTOs, no extensions, no methods
 *     The cluster197 4-leaf set is the CLEANEST architectural posture in the :en/ tier —
 *     no Volatile cache, no Cloudflare gating, no network coupling. Pure-function over
 *     HTML/JSON → Domain mapping.
 *
 * Cluster197 4-leaf arc closes here. :en/ Repository tier (excluding the comick_io/models
 * small-file subtree of ~16 files at 10-40 lines each) is FULLY SWEPT after this commit.
 *
 * Next cluster (198) candidates: comick_io/models/ small-file subtree (~16 files, mostly
 * 10-40 lines each — likely a single-cluster joint-postscript sweep), or the next-language
 * Repository tier opening (any of :ar/:es/:fr/:in/:it/:pt/:ru/:tr — `find` shows :ar/ is
 * already swept clusters 191-194; the remaining 7 language-pack subtrees are unscouted).
 */