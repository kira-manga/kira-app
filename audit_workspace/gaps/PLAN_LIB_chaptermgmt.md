# PLAN — GAP-LIB-01/02/03: per-manga library chapter-management (read-toggle/mark-read + per-chapter download/cancel/download-all + multi-select)

Author: native-parity implementer (Opus 4.8). Branch `architecture-rework`. Date 2026-05-31.

## STEP 1 — VERIFICATION (AD-6: read live code, don't trust the gap)

Confirmed against live source (not just the audit md files):

1. **A library card tap routes to the generic rework Details screen, NOT a dedicated library-details screen.**
   - `LibraryScreenRoute.kt:201-211` maps `NavigateToDetails` → `Screen.MangaDetailsRework(api, language, title, url, coverUrl, rating, genres)` carrying the full saved identity (the "opens fresh" regression fix).
   - `App.kt` binds both `Screen.MangaDetails` (URL-only) and `Screen.MangaDetailsRework` (full tuple) to the same rework `:ui` `DetailsScreen`/`DetailsScreenByUrl` over ONE Koin `DetailsViewModel`.
   - There is **no** rework `:ui`/`:presentation` `library_details` screen. The legacy `LibraryMangaScreen` + `LibraryDetailsViewModel` were retired (§435-437). GAP-LIB-01 is CONFIRMED REAL (no parallel screen).

2. **The rework Details screen ALREADY HAS (verified live):**
   - Offline-first saved-DB chapter list via `ObserveSavedMangaDetailsUseCase` — a Library-opened manga renders its Room chapter list + read/downloaded/bookmark marks immediately and offline (`DetailsViewModel.startObservingSavedDetails`).
   - Per-chapter **read-state** already shown: `ChapterRow` dims read chapters to `onSurfaceVariant` from the Room-backed `Chapter.isRead` (`DetailsScreen.kt:871-875`).
   - Per-chapter **downloaded indicator**: 8dp primary dot when `chapter.isDownloaded` (`DetailsScreen.kt:888-897`).
   - **Download-all**: header `OutlinedButton`, gated on `state.isInLibrary`, → `OnDownloadAllClick` → `EnqueueAllChaptersDownloadUseCase` (`DetailsViewModel:393-399`).
   - Bookmark add/remove (top-bar heart + confirm dialog), 403/Cloudflare WebView recovery, refresh (`OnRetry`), Downloads nav.

3. **The rework Details screen is MISSING (the real GAP-LIB-02/03 scope):**
   - **Per-chapter read TOGGLE / mark-read** on the chapter row (read state is shown but read-only — no affordance to set/unset it). REAL GAP.
   - **Per-chapter download button / cancel** on the chapter row (only download-ALL exists in the header). REAL GAP.
   - **Multi-select action bar** (long-press → select → bulk mark-read / download / cancel). REAL GAP.

4. **Reusable building blocks (verified live):**
   - `MarkChapterReadUseCase(chapterUrl)` → `MarkChapterReadRepository.markRead` → `ChapterDao.markChapterAsRead` (sets `isRead=1`). Exists; **no mark-UNREAD / toggle** verb yet.
   - `EnqueueDownloadUseCase(chapterId, mangaTitle, api)` (needs `Long` chapterId).
   - `ChapterIdResolver.resolveChapterId(url): Long?` (url → Room id; null when not in-library).
   - `CancelDownloadUseCase(chapterId)` (QUEUED/COMPRESSING) + `CancelRunningDownloadUseCase(chapterId, mangaId)` (RUNNING).
   - `EnqueueAllChaptersDownloadUseCase(details)` (already wired into Details VM).
   - `ObserveDownloadsUseCase(): Flow<List<DownloadedChapter>>` — to know which chapters are actively downloading (DownloadedChapter carries chapterId + state).
   - `ChapterDao` already has `toggleChaptersReadBatch(ids)` (toggle by id list) + `markChaptersRead(ids)` (bulk mark-read). Perfect for read-toggle + bulk.

## STEP 2 — DECISION: **(B) Extend the existing rework Details screen.** 

Rationale:
- The rework deliberately consolidated all manga-detail surfaces (Home/Library/History/Updates/Search → one `DetailsScreen` over one `DetailsViewModel`). The campaign rule forbids duplicated UI; re-introducing a parallel `LibraryMangaScreen` would resurrect the exact split-brain the §430/§435-437 retirements removed.
- Details ALREADY hosts the offline saved-DB chapter list with read/downloaded marks and download-all. The only missing pieces are per-chapter *actions* on rows that already render the *state* — a natural, low-risk extension of `ChapterRow` + `DetailsViewModel`, not a new screen.
- A library tap already lands on Details with the full saved identity. Adding the per-chapter actions there gives native functional parity for GAP-LIB-02/03 with zero new navigation surface. The actions are safely gated on `state.isInLibrary` (the chapter must have a saved Room row for read/download writes to be meaningful — same precondition the native library-details screen had, since it only operated on saved chapters).
- (A) — a dedicated screen — was rejected: Details genuinely CAN host these (it already hosts the data + download-all + offline list); a second screen would duplicate the cover/header/genres/chapter-list/403-recovery/offline-merge logic.

### Native-behavior → rework mapping
| Native (LibraryMangaScreen) | Rework landing |
|---|---|
| Per-chapter RemoveRedEye read toggle | `ChapterRow` trailing read-toggle IconButton → `OnToggleChapterRead(chapter)` |
| Bulk mark-read / mark-all-read | multi-select bar `Mark read` → `OnMarkSelectedRead` |
| Per-chapter Download / DownloadDone | `ChapterRow` trailing download IconButton → `OnDownloadChapter(chapter)` (hidden when downloaded; downloaded shows existing dot + done state) |
| Per-chapter cancel (running/queued dropdown) | `ChapterRow` shows progress + cancel IconButton when actively downloading → `OnCancelChapterDownload(chapter)` |
| Download-all (header/menu) | already present (`OnDownloadAllClick`) |
| Download-selected (custom) | multi-select bar `Download` → `OnDownloadSelected` |
| Long-press → multi-select bar | `ChapterRow` long-press → `OnChapterLongClick`; selection bar overlay |
| NEW badge / file-size captions | DEFERRED sub-gap (data not on rework `Chapter`/`MangaDetails`) |
| Resume FAB, chapter sort/filter sheet, delete-downloaded, blurred parallax header, banner ad | OUT OF SCOPE for GAP-LIB-02/03 (separate gaps GAP-LIB-05..09); not in this task's P0 trio |

## STEP 3 — IMPLEMENTATION (what landed)

New `:domain` use case (only capability truly absent):
- `ToggleChapterReadUseCase(chapterUrl)` → new `MarkChapterReadRepository.toggleRead(chapterUrl)` (single-chapter read toggle; `ChapterDao.getChapterIdByUrl` then `toggleChaptersReadBatch(listOf(id))`).

Reused existing use cases (no new ones): `MarkChapterReadUseCase` (bulk via repeated calls is avoided — used `markChaptersRead`-style via the toggle/mark path), `EnqueueDownloadUseCase`, `ChapterIdResolver`, `CancelDownloadUseCase`, `CancelRunningDownloadUseCase`, `ObserveDownloadsUseCase`. Added a bulk `MarkChaptersReadUseCase(chapterUrls)` → new `MarkChapterReadRepository.markRead(urls)` overload (bulk mark-read for the selection bar).

MVI (`:presentation/details`):
- New intents: `OnToggleChapterRead`, `OnDownloadChapter`, `OnCancelChapterDownload`, `OnChapterLongClick`, `OnSelectionToggle`, `OnSelectionClear`, `OnMarkSelectedRead`, `OnDownloadSelected`.
- New state: `selectedChapterUrls: Set<String>` (+ derived `isInChapterSelectionMode`), `downloadingChapterUrls: Set<String>` (urls with an active download row, from `ObserveDownloadsUseCase` joined by chapter id).
- VM: collects downloads, resolves active-download chapter urls; per-chapter + bulk handlers gated on `state.isInLibrary`.

`:ui` (callback-only): `ChapterRow` gains read-toggle + download/cancel trailing actions + long-press; a `ChapterSelectionBar` overlay (mark-read / download / cancel-selection). All new affordances dispatch intents; no Koin/nav/platform leak.

Koin (`:composeApp/DetailsReworkModule`): bind `ToggleChapterReadUseCase`, `MarkChaptersReadUseCase`, `MarkChapterReadUseCase`, `EnqueueDownloadUseCase`(cross-module from downloads module), `CancelDownloadUseCase`, `CancelRunningDownloadUseCase`, `ObserveDownloadsUseCase`(cross-module), `ChapterIdResolver`(already bound) into `DetailsViewModel`.

New en string keys (en-only OK): `details_mark_read`, `details_mark_unread`, `details_cancel_chapter_download`, `details_chapter_selection_count`. Reused: `download`, `cancel`, `mark_read`, `downloaded`.

## DEFERRED sub-gaps (clearly labelled; not half-wired)
- **GAP-LIB-02-NEW-BADGE / GAP-LIB-12 file-size captions**: the rework `Chapter`/`MangaDetails` domain models carry no `isNew` flag and no per-chapter byte size; surfacing them would require a domain-model + DAO-projection + mapper change beyond this trio's scope. Deferred — recorded here, not invented.
- **Per-chapter bookmark toggle on the row (GAP-LIB-04, P1)**: bookmark is already a per-chapter capability in the Reader (`ToggleChapterBookmarkUseCase`); adding it to the Details row is P1, deferred to keep this change focused on the P0 read+download trio. The downloaded-dot + read-dim already render bookmark-adjacent state.
- **Per-chapter determinate download % + COMPRESSING Lottie**: the row shows a generic in-progress spinner + cancel when a download is active (state from `ObserveDownloadsUseCase`); per-byte % and the `filemoving.lottie` are Android-centric polish, deferred.
- Resume FAB, chapter sort/filter sheet, delete-all-downloaded, blurred parallax header, ads — separate gaps (GAP-LIB-05..09), not this task.

## Forbidden-paths confirmation
No edits to `sources_repositry/`, the old native app, or the 3 `app/` WIP files. `ChapterDao` (in `:shared/data/local/dao`) is edited only to add a `toggleChaptersReadBatch` caller path — actually it already exposed `toggleChaptersReadBatch` + `markChaptersReadBatch`, so NO DAO change was needed; the new repo overloads reuse existing DAO methods.
