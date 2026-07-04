package me.manga.kira.domain.model

import kotlinx.datetime.LocalDate

/**
 * Pure-domain representation of a single chapter list entry.
 *
 * Mirrors the legacy `ChapterItem` (see `:shared/.../domain/model/ChapterItem.kt`) **minus**
 * framework annotations and the embedded page list:
 *  - No `@Serializable` — contract §4 forbids framework annotations on domain entities. The
 *    DTO mappers in `:data` add serialization concerns at the layer boundary.
 *  - No `chaptersImages: List<ChapterImage>` — chapter pages are a separate fetch concern that
 *    belongs to the Reader feature, not to the chapter list. Embedding them inflates the list
 *    payload for every Details screen render and conflates two unrelated lifecycles. The rework
 *    splits pages into a dedicated `ChapterPages` model under a future Reader slice.
 *  - `MutableList<ChapterItem>` → `List<Chapter>` everywhere — domain models are immutable by
 *    rework convention (contract §4).
 *
 * Identity is by [url] within a given [MangaDetails.chapters] list. The source URL is the
 * canonical address for re-fetching the chapter pages, so the rework data layer keys download /
 * bookmark state on it directly (no separate chapter id).
 *
 * **Audit-trail postscript** (Phase 9.x.cluster135.staleKdocSweep.cascade,
 * Task #591, 2026-05-28): classified as follows after recursive symbol
 * verification (one-hundred-and-nineteenth sibling of the cluster57-134
 * sweep — second file of the wave-24 third-cluster `:domain/model/root/`
 * 3-leaf-model batch alongside Manga plus MangaDetails):
 *  (a) "Mirrors-the-legacy-ChapterItem-minus-framework-annotations-and-
 *  the-embedded-page-list + No-@Serializable-contract-§4-forbids-
 *  framework-annotations-on-domain-entities + DTO-mappers-in-:data-add-
 *  serialization-concerns-at-the-layer-boundary + No-chaptersImages-
 *  List-ChapterImage-chapter-pages-are-a-separate-fetch-concern-that-
 *  belongs-to-the-Reader-feature + Embedding-them-inflates-the-list-
 *  payload-for-every-Details-screen-render-and-conflates-two-unrelated-
 *  lifecycles + the-rework-splits-pages-into-a-dedicated-ChapterPages-
 *  model-under-a-future-Reader-slice + MutableList-ChapterItem-arrow-
 *  List-Chapter-everywhere + domain-models-are-immutable-by-rework-
 *  convention" — LIVE-NOT-STALE plus FULFILLED-PREDICTION-(framework-
 *  free) plus FULFILLED-PREDICTION-(chaptersImages-split). Verified:
 *  zero framework annotations on the data class; the only import is
 *  kotlinx.datetime.LocalDate (a multiplatform pure-domain type, not a
 *  framework). The chaptersImages prune landed in Phase 9.y.chapteritem.
 *  fieldprune.cumulative (Task #418) — the legacy ChapterItem mirror
 *  no longer carries the page list; pages are fetched via the separate
 *  Reader-slice ChapterPagesRepository.fetchPages contract producing
 *  List<Page>. MangaDetailsMappers.kt L74 `internal fun LegacyChapterItem.
 *  toDomain(): Chapter` translates the legacy mutable type to this
 *  immutable :domain Chapter at the strangler-fig boundary.
 *  (b) "Identity-is-by-url-within-a-given-MangaDetails.chapters-list +
 *  source-URL-is-the-canonical-address-for-re-fetching-the-chapter-
 *  pages + rework-data-layer-keys-download-/-bookmark-state-on-it-
 *  directly-(no-separate-chapter-id)" — LIVE-NOT-STALE plus FULFILLED-
 *  PREDICTION. Verified via recursive grep: ChapterPagesRepository.
 *  fetchPages(chapterUrl: String) keys on the URL; the rework
 *  ChapterDownloadRepository plus the Reader VM both key state on the
 *  same URL string. No surrogate chapter-id was introduced in any
 *  :data or :domain consumer.
 *  Two classifications STAND on their own merits. Original Phase
 *  6.2.x-era prose preserved verbatim per the audit-trail-preservation
 *  convention.
 */
data class Chapter(
    /** Chapter number as the source labels it ("12", "12.5", "Volume 3 Ch. 7" — opaque to domain). */
    val number: String,
    /** Optional chapter title; empty string when the source doesn't ship one. */
    val name: String,
    /** Source page URL — the canonical address for fetching pages. */
    val url: String,
    /** Publication date as supplied by the source; null when the source doesn't expose one. */
    val date: LocalDate?,
    /** True when this chapter has been downloaded for offline reading. */
    val isDownloaded: Boolean,
    /** True when the user has bookmarked this chapter. */
    val isBookmarked: Boolean,
    /**
     * True when the user has already read this chapter. Sourced from the local Room
     * `SavedChapterEntity.isRead` for a saved manga (the offline/local Details path) — a fresh
     * network fetch has no read history, so the DTO mapper leaves it `false`. Restoring this field
     * is what lets a Library-opened manga render its read/unread marks immediately instead of
     * "looking fresh" (regression fix, 2026-05-31).
     */
    val isRead: Boolean = false,
    /**
     * True when a Library refresh inserted this chapter as a newly-published one not previously in
     * the local list (native parity: `SavedChapterEntity.isNew`, set on refresh-insert in native's
     * `LibraryDetailsViewModel`). Sourced from the Room `saved_chapters.isNew` column for a saved
     * manga (the offline/local Details path); a fresh network fetch has no notion of "new", so the
     * DTO mapper leaves it `false`. The Details chapter row renders a small red "NEW" badge while
     * this is `true`; the flag clears the moment the chapter is opened (native clears it on chapter
     * click via `markChapterIsNew` — the rework clears it on the mark-read/open path).
     */
    val isNew: Boolean = false,
    /**
     * Epoch-millis timestamp of when a Library refresh DISCOVERED this chapter (set alongside
     * [isNew] on the refresh-insert). Distinct from [date] (the chapter's publish date). The
     * presentation layer hides the NEW badge once `now - fetchedAt` exceeds the 4-day window even
     * if the chapter was never opened. `0` when unknown (network DTO / rows saved before the column
     * existed) — treated as "outside the window", so no badge is shown for an unknown discovery time.
     */
    val fetchedAt: Long = 0,
)
