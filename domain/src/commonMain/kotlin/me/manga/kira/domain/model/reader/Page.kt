package me.manga.kira.domain.model.reader

/**
 * Pure-domain representation of a single reader page.
 *
 * Mirrors the legacy `ReaderItem.ImagePage` (see `:shared/.../reader/data/ReaderItem.kt`) **minus**
 * presentation-layer carry-throughs:
 *  - No `chapterIndex` — that field belongs to the presentation-layer timeline (which slot in the
 *    `LazyColumn`/Pager backing the reader the page renders into). The domain layer hands back the
 *    raw page sequence; the presentation layer pairs each page with the chapter context it came
 *    from when it assembles `ReaderItem` timeline entries.
 *  - No `BitmapPainter` / `ImageRequest` / compression fields — those are Coil/Compose-UI concerns
 *    that live above the `:ui` layer. The legacy `:shared` port already stripped them; the rework
 *    refuses them at the domain boundary by construction.
 *
 * Field rationale:
 *  - [url] is the canonical address for fetching the page bitmap. For streamed sources (Prochan)
 *    it's an HTTP URL; for downloaded chapters it's a local file path string (which Coil's `model`
 *    handles uniformly — see `ChapterPagesRepository.fetchPages` KDoc).
 *  - [headers] is the per-source HTTP header map (referer / user-agent / source-specific tokens)
 *    that the `:ui` layer plugs into Coil's `ImageRequest.Builder.httpHeaders(...)`. It's per-page
 *    rather than per-chapter because some future source families set per-page tokens (legacy
 *    pulls these from `BaseMangaRepository.defaultHeaders`, which is per-source today but the
 *    field on `Page` keeps the seam open). Empty map = no extra headers needed.
 *
 * Immutable by rework convention (contract §4).
 *
 * **Audit-trail postscript** (Phase 9.x.cluster134.staleKdocSweep.cascade,
 * Task #590, 2026-05-28): classified as follows after recursive symbol
 * verification (one-hundred-and-fifteenth sibling of the cluster57-133
 * sweep — first file of the wave-24 second-cluster `:domain/model/reader/`
 * 3-leaf-model batch alongside ReadingMode plus PageDownloadProgress;
 * opens cluster134):
 *  (a) "Mirrors-the-legacy-ReaderItem.ImagePage-minus-presentation-
 *  layer-carry-throughs + No-chapterIndex-that-field-belongs-to-
 *  presentation-layer-timeline-which-slot-in-LazyColumn-or-Pager-
 *  backing-the-reader-the-page-renders-into + No-BitmapPainter-
 *  ImageRequest-compression-fields-those-are-Coil-or-Compose-UI-
 *  concerns-that-live-above-the-:ui-layer + legacy-:shared-port-
 *  already-stripped-them + rework-refuses-them-at-the-domain-boundary-
 *  by-construction" — LIVE-NOT-STALE + FULFILLED-PREDICTION. Verified
 *  via recursive grep: Page is consumed by ChapterPagesRepository.
 *  fetchPages (:domain contract) plus ChapterPagesRepositoryImpl
 *  (:data) plus ReaderState.pages list (:presentation) plus
 *  ReaderViewModel plus ReaderScreen (:ui). The :domain model
 *  contains url plus headers fields ONLY — no chapterIndex, no
 *  BitmapPainter, no ImageRequest. The presentation-layer timeline
 *  concerns (chapter indexing within the LazyColumn/Pager) live in
 *  ReaderState's per-chapter wrapper metadata, not on the Page model
 *  itself.
 *  (b) "url-is-the-canonical-address-for-fetching-the-page-bitmap +
 *  for-streamed-sources-Prochan-HTTP-URL + for-downloaded-chapters-
 *  local-file-path-string-which-Coil-model-handles-uniformly +
 *  headers-is-the-per-source-HTTP-header-map-(referer-user-agent-
 *  source-specific-tokens)-that-:ui-layer-plugs-into-Coil-ImageRequest.
 *  Builder.httpHeaders + per-page-rather-than-per-chapter-because-
 *  some-future-source-families-set-per-page-tokens + legacy-pulls-
 *  these-from-BaseMangaRepository.defaultHeaders-which-is-per-source-
 *  today-but-the-field-on-Page-keeps-the-seam-open + Empty-map = no-
 *  extra-headers-needed" — LIVE-NOT-STALE + FULFILLED-PREDICTION-
 *  (header-attach) + FORECAST-NOT-YET-FULFILLED-(per-page-token-
 *  divergence). Verified via ChapterPagesRepositoryImpl.kt L83
 *  `val headers = sourceRepo.defaultHeaders` — the source-repo
 *  defaultHeaders are attached per-page to every Page emitted from
 *  fetchPages. The :ui ReaderScreen consumes page.headers via Coil's
 *  ImageRequest.Builder.httpHeaders extension. The forecast that
 *  "future source families set per-page tokens" remains FORECAST-NOT-
 *  YET-FULFILLED — today all pages within a single chapter share the
 *  same per-source defaultHeaders snapshot (per-page divergence has
 *  not been observed in production sources). The data shape keeps
 *  the per-page seam open for that future extension.
 *  Two classifications STAND on their own merits. Opens cluster134.
 *  Original Phase 6.4.1-era prose preserved verbatim per the audit-
 *  trail-preservation convention.
 */
data class Page(
    val url: String,
    val headers: Map<String, String>,
)
