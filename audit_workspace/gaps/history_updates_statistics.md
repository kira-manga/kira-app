## History, Updates & Statistics — gaps

24 gaps identified (History 7, Updates 10, Statistics 5, cross-cluster 2): 3 P0, 9 P1, 8 P2, 4 P3.

---

## History

### GAP-HIST-01 — Cover thumbnail loads without `buildImageRequest` parity
- **Screen/surface:** HistoryScreen — per-row cover thumbnail
- **Type:** BEHAVIOR
- **OLD (target):** Cover `AsyncImage` model comes from `buildImageRequest(context, item.mangaImageUrl, item.api)` and an explicit `imageLoader = getImageLoader()` (old `HistoryItem.kt:67-85`; VM exposes `buildImageRequest` passed down via `HistoryRoute.kt:16-37`).
- **KMP (current):** Plain `AsyncImage(model = url)` on the singleton ImageLoader, no per-request builder (`HistoryScreen.kt:271-284`; cluster note `kmp:77`).
- **Priority:** P1
- **Acceptance criteria:** History cover requests carry the same per-source headers/referer/size/decoder configuration as the native `buildImageRequest`. For a source requiring referer headers (e.g. Azora-type), the History thumbnail loads where it currently would 403/fail. If the singleton ImageLoader's interceptor already injects per-`api` headers, document that equivalence; otherwise add a request builder.
- **Notes:** Directly tied to MEMORY notes on `buildImageRequest` / OkHttp fetcher / header interceptor parity. Verify whether the singleton's header interceptor keys off the request URL host or needs the `api` hint that native passed explicitly. Same concern as GAP-UPD-01.

### GAP-HIST-02 — Cover dimensions changed 80×120 → 72×108
- **Screen/surface:** HistoryScreen — cover card
- **Type:** VISUAL
- **OLD (target):** Cover card 80×120 dp with `shapes.small` (old `HistoryItem.kt:67-85`, visual `old:17`).
- **KMP (current):** Cover 72×108 dp with `RoundedCornerShape(6.dp)` clip + `background.copy(alpha=0.15f)` tint (`HistoryScreen.kt:270-284`).
- **Priority:** P2
- **Acceptance criteria:** History cover renders at 80×120 dp to match native (or this delta is explicitly accepted as the rework design-system sizing). Corner radius matches the design-system `shapes.small` equivalent.
- **Notes:** Same 72×108 idiom is shared with Updates (GAP-UPD-02); fix both together if reverting to 80×120.

### GAP-HIST-03 — Card container color/shape divergence
- **Screen/surface:** HistoryScreen — row card
- **Type:** VISUAL
- **OLD (target):** Card container color `surface.copy(alpha=0.12f)`, default M3 card corner, no faint cover bg tint (old `HistoryItem.kt:52-60`, `old:17`).
- **KMP (current):** Card `containerColor = surfaceVariant`, `RoundedCornerShape(12.dp)`, cover gets a `background.copy(alpha=0.15f)` tint behind the image (`HistoryScreen.kt:254-282`).
- **Priority:** P2
- **Acceptance criteria:** Row card background and corner match native (`surface @ 0.12` alpha, default M3 corner) or the surfaceVariant choice is accepted as the unified design-system token. Cover background tint matches native (native has none).
- **Notes:** Cosmetic; resolve alongside GAP-HIST-02 as one visual-parity pass on `HistoryRow`.

### GAP-HIST-04 — Delete/Clear-all are text buttons instead of icons
- **Screen/surface:** HistoryScreen — top-bar action + per-row delete
- **Type:** DEVIATION(intentional design-system)
- **OLD (target):** Top-bar `DeleteForever` `IconButton` tinted `error`; per-row outlined `Delete` `IconButton` tinted `onBackground` (old `HistoryScreen.kt:55-61`, `HistoryItem.kt:114-120`).
- **KMP (current):** Top-bar "Clear all" `TextButton` (error-colored) and per-row "Delete" `TextButton` (error-colored); `:ui` omits `materialIconsExtended` (`HistoryScreen.kt:181-186,313-315`; cluster note `kmp:69`).
- **Priority:** P2
- **Acceptance criteria:** Decide cluster-wide whether icon affordances are restored. The shared design system already exposes `YamiIcons`, so an icon-based affordance is feasible without `materialIconsExtended`. If text buttons are the accepted rework posture, mark this resolved-by-design; otherwise restore icon buttons (DeleteForever / Delete) for native parity.
- **Notes:** Spans all three screens (also GAP-UPD-09, GAP-STAT-04). Single product decision should cover the whole cluster; `YamiIcons` is the shared path.

### GAP-HIST-05 — Per-row delete tint changed (onBackground → error)
- **Screen/surface:** HistoryScreen — per-row delete affordance
- **Type:** VISUAL
- **OLD (target):** Per-row delete icon tinted `colorScheme.onBackground` (only the top-bar clear-all is `error`) (old `HistoryItem.kt:114-120`, `old:17`).
- **KMP (current):** Per-row "Delete" colored `colorScheme.error` (same as Clear-all) (`HistoryScreen.kt:314`).
- **Priority:** P3
- **Acceptance criteria:** Per-row delete uses `onBackground` tint matching native, with only the clear-all action carrying the destructive `error` color — OR the unified-error coloring is accepted as intentional.
- **Notes:** Minor; bundle with GAP-HIST-04 affordance decision.

### GAP-HIST-06 — History now has loading + empty UI (native rendered neither)
- **Screen/surface:** HistoryScreen — loading & empty states
- **Type:** KMP-EXTRA
- **OLD (target):** `isLoading`/`error` exist in state but the screen never reads them; empty list renders blank background; no spinner, no empty illustration (old `HistoryScreen.kt`, `old:19-21`).
- **KMP (current):** loading → shared `YamiLoadingState` spinner; empty → shared `YamiEmptyState(no_reading_history)` illustration (`HistoryScreen.kt:198-199`; `YamiStateViews.kt:48-112`).
- **Priority:** P3
- **Acceptance criteria:** Confirm the added loading/empty states are an accepted intentional improvement (rework tasks #239/#288/#357 indicate History was reworked). No action unless strict pixel-parity with the blank native screen is required.
- **Notes:** Improvement, not a regression. Document as accepted KMP-EXTRA. Aligns shared-component reuse (see GAP-UPD-08).

### GAP-HIST-07 — Absolute-date group header is English-only
- **Screen/surface:** HistoryScreen — sticky date-group header (>6 days old)
- **Type:** BEHAVIOR
- **OLD (target):** `formatGroupLabel` returns `"MMM d, yyyy"` for entries older than 6 days using the platform date formatter (old `HistoryScreen.kt:107-128`); on Android this respects locale.
- **KMP (current):** `monthAbbrev` returns hard-coded English "Jan".."Dec"; only relative keys (today/yesterday/N-ago) are localized (`HistoryScreen.kt:332-376`; cluster note `kmp:73`).
- **Priority:** P2
- **Acceptance criteria:** Group headers for entries >6 days old render localized month names (Arabic under RTL), matching native locale behavior, or use `kotlinx-datetime` + localized resources rather than a hard-coded English array.
- **Notes:** Same defect in Updates (GAP-UPD-10). Both lists copy-paste `monthAbbrev`; fixing the shared helper resolves both (see GAP-X-01 dedup).

---

## Updates

### GAP-UPD-01 — Thumbnail request parity (and native used RAW url; KMP uses singleton)
- **Screen/surface:** UpdatesScreen — per-row thumbnail
- **Type:** BEHAVIOR
- **OLD (target):** Native fed the RAW `notification.mangaImageUrl` straight into `AsyncImage` with NO `buildImageRequest` (old `UpdateItem.kt:61-71`; explicitly flagged as a divergence/quality risk `old:98`).
- **KMP (current):** Plain `AsyncImage(model = url)` on the singleton ImageLoader (AVIF/OkHttp/header-interceptor/Skia) (`UpdatesScreen.kt:367-380`; `kmp:77`).
- **Priority:** P1
- **Acceptance criteria:** Updates thumbnails load reliably for header/referer-gated sources. The KMP singleton path is arguably BETTER than native's raw-url path; confirm the singleton's header interceptor covers the `api`/source that native omitted. If parity-with-native means "raw url" the KMP behavior should still be kept (it fixes a latent native bug) and documented as an intentional improvement.
- **Notes:** Native's raw-url approach was itself a likely bug. Treat KMP singleton as the desired target but verify header coverage. Linked to GAP-HIST-01.

### GAP-UPD-02 — Thumbnail size changed 50×50 → 72×108
- **Screen/surface:** UpdatesScreen — per-row thumbnail
- **Type:** VISUAL
- **OLD (target):** 50×50 dp `AsyncImage`, `clip(RoundedCornerShape(8.dp))`, bg `surfaceVariant` (old `UpdateItem.kt:61-71`, `old:37`).
- **KMP (current):** 72×108 dp cover with 6dp clip + alpha-0.15 tint — same idiom as History (`UpdatesScreen.kt:367-380`).
- **Priority:** P1
- **Acceptance criteria:** Updates thumbnail renders at native's 50×50 square with 8dp corners — OR the 72×108 portrait cover is an explicitly accepted rework redesign of the Updates row. This is a noticeable layout change (square badge vs full portrait cover), so a deliberate decision is required.
- **Notes:** The native Updates row is visually compact (square thumb + dot + chapter#); the KMP row mirrors the History card. Confirm intended design.

### GAP-UPD-03 — Per-row download spinner (queued/running states) missing
- **Screen/surface:** UpdatesScreen — per-row download affordance
- **Type:** MISSING_FEATURE
- **OLD (target):** Trailing affordance has THREE states: 24dp `CircularProgressIndicator` (strokeWidth 2dp; `primary` tint when this is the running chapter, `onPrimary` when merely queued) while downloading, else a 48dp download `IconButton` (`Download` idle / `DownloadDone` when downloaded). Derived from `downloadViewModel.queuedChapterIds` + `runningChapter` injected via the route (old `UpdateItem.kt:113-137`, `NotificationsRoute.kt:31-32`, `old:36,97`).
- **KMP (current):** Single "Download"/"Downloaded" `TextButton`, disabled when downloaded; enqueue-only via `EnqueueDownloadUseCase`. No queued/running progress indicator (`UpdatesScreen.kt:417-428`; feature inventory `kmp:41` notes it was DEFERRED then replaced by enqueue-only; `UpdatesReworkScreenRoute.kt:53-87`).
- **Priority:** P1
- **Acceptance criteria:** Each row reflects live download progress: idle → download affordance; queued → spinner; running → distinct (primary-tinted) spinner; done → "Downloaded"/done affordance. Wire the download-queue/running state into `UpdatesState`/row so progress is observable, matching native.
- **Notes:** Requires surfacing download-manager queued/running state into the presentation layer (rework slices #299/#300). Largest functional Updates gap.

### GAP-UPD-04 — Interstitial-ad gate on download not invoked
- **Screen/surface:** UpdatesScreen — download action
- **Type:** MISSING_FEATURE
- **OLD (target):** Download tap calls `downloadViewModel.downloadChapterNotification(...)` then `adViewModel.onDownloadStarted(context, ...)` — an interstitial-ad gate (old `NotificationsRoute.kt:42-50`, `old:46,97`).
- **KMP (current):** Download dispatches `OnDownloadClick` → `EnqueueDownloadUseCase` only; no ad gate (`UpdatesViewModel.kt:166-176`).
- **Priority:** P2
- **Acceptance criteria:** Confirm whether the interstitial-ad gate is part of target parity. If ads are retained in the rework, the Updates download path must invoke the ad gate consistently with other download surfaces (Library/Details). If ads were intentionally dropped cluster-wide, document as accepted.
- **Notes:** Cross-feature with the Ad subsystem; verify against the global ad-strategy decision rather than fixing in isolation. Could be DEVIATION if ads are Android-only.

### GAP-UPD-05 — Swipe-to-mark-read / swipe-to-delete gestures replaced by buttons
- **Screen/surface:** UpdatesScreen — row gestures
- **Type:** BEHAVIOR
- **OLD (target):** `SwipeToDismissBox` both directions: swipe-right (StartToEnd) → mark read (Done icon, primary@0.2 bg); swipe-left (EndToStart) → delete-with-undo (Delete icon, error@0.2 bg); animated 300ms color crossfade; `confirmValueChange` always false so the row performs the side-effect and snaps back (old `UpdatesScreen.kt:163-214`, `old:35,47-49,99`).
- **KMP (current):** No swipe gestures. Mark-read and Delete are stacked `TextButton`s in the row's trailing column; "Delete" still produces an undo snackbar (`UpdatesScreen.kt:410-432`; inventory `kmp:36,41` — "legacy swipe-to-dismiss reframed as per-row Delete button").
- **Priority:** P1
- **Acceptance criteria:** Decide whether native swipe affordances are restored. If parity requires swipe, implement a multiplatform swipe-to-dismiss (mark-read on right, delete-with-undo on left, perform-side-effect-without-settling semantics). If the button reframe is accepted, document as intentional and ensure discoverability matches.
- **Notes:** `SwipeToDismissBox` is multiplatform in Compose; the "perform side-effect, don't dismiss" pattern (`confirmValueChange=false`) must be replicated. Significant UX delta from native.

### GAP-UPD-06 — Unread dot indicator missing; styling differs
- **Screen/surface:** UpdatesScreen — unread indication
- **Type:** VISUAL
- **OLD (target):** Unread rows show an 8dp `primary` `CircleShape` dot before the chapter number; read rows dim to **0.4 alpha**; title 16.sp `titleMedium`, chapter# 12.sp `bodyMedium` (old `UpdateItem.kt:73-112`, `old:36-37`).
- **KMP (current):** No unread dot. Unread → `FontWeight.SemiBold` title + full alpha; read → `Normal` weight + **0.60 alpha** content (`UpdatesScreen.kt:348-349,388-407`).
- **Priority:** P2
- **Acceptance criteria:** Add the 8dp primary unread dot adjacent to the chapter number, and align read-state dimming to native's 0.4 alpha (currently 0.6) — or accept the weight-based scheme as the rework standard and reconcile the alpha value.
- **Notes:** Two sub-deltas: missing dot + alpha 0.4 vs 0.6. Dot is the clearer parity miss.

### GAP-UPD-07 — Recency-bucket grouping (Last Week / Older) vs calendar-date headers + chapter-number sort
- **Screen/surface:** UpdatesScreen — grouping & sort
- **Type:** BEHAVIOR
- **OLD (target):** Fixed recency buckets — Today / Yesterday / Last Week (>today-7 & <yesterday) / Older — each sorted **descending by chapter number** (`chapterNumber.toDoubleOrNull() ?: 0.0`); header strings `notifications_group_today/yesterday/last_week/older` (old `NotificationRepository.kt:45-72`, `old:53,101`).
- **KMP (current):** Updates uses the SAME calendar-date `groupByDate`/`formatGroupLabel` as History (Today / Yesterday / "N days ago" / "MMM d, yyyy"), grouped by `notificationDate`, not the 4 fixed recency buckets; sort is by date not chapter number (`UpdatesScreen.kt:311-322,443-494`; cluster note `kmp:72`).
- **Priority:** P1
- **Acceptance criteria:** Updates groups into Today / Yesterday / Last Week / Older buckets with within-bucket descending chapter-number sort, matching native — OR the calendar-date scheme is explicitly accepted. Header labels must match the native bucket strings.
- **Notes:** Native deliberately uses a DIFFERENT grouping scheme from History; the KMP port collapsed both onto History's scheme. Port the native bucket logic verbatim. Watch the chapter-number numeric sort.

### GAP-UPD-08 — Updates loading/empty state doesn't reuse shared components
- **Screen/surface:** UpdatesScreen — loading & empty states
- **Type:** REFACTOR
- **OLD (target):** Native used a shared `LoadingScreen()` (centered `CircularProgressIndicator`, color `inversePrimary`) for loading; no empty composable (old `UpdatesScreen.kt:108-109`, `LoadingScreen.kt:18-33`, `old:39-40`).
- **KMP (current):** Hand-rolled bare `CircularProgressIndicator` for loading and a plain `Text(no_updates)` for empty — does NOT use the shared `YamiLoadingState`/`YamiEmptyState` that History uses (`UpdatesScreen.kt:283-289`; cluster note `kmp:70`).
- **Priority:** P2
- **Acceptance criteria:** Updates uses the shared `YamiLoadingState` and `YamiEmptyState` components (matching History) for visual consistency across the cluster.
- **Notes:** Shared components exist (`YamiStateViews.kt`); purely a reuse/consistency fix. Pairs with GAP-STAT-03.

### GAP-UPD-09 — Action affordances are text buttons instead of icons (DoneAll/DeleteSweep/Download)
- **Screen/surface:** UpdatesScreen — top-bar + per-row affordances
- **Type:** DEVIATION(intentional design-system)
- **OLD (target):** Top-bar `DeleteSweep` (delete-all) + `DoneAll` (mark-all-read) icon buttons, disabled when empty; per-row `Done`/`Delete`/`Download`/`DownloadDone` icons (old `UpdatesScreen.kt:82-93`, `UpdateItem.kt`, `old:34-36`).
- **KMP (current):** "Mark all read"/"Clear all" `TextButton`s in top bar; per-row "Mark read"/"Download"/"Delete" `TextButton`s; `:ui` omits `materialIconsExtended` (`UpdatesScreen.kt:259-270,410-432`; `kmp:69`).
- **Priority:** P2
- **Acceptance criteria:** Same cluster-wide icon-vs-text decision as GAP-HIST-04. Restore icon affordances via `YamiIcons` or accept text-button posture as the rework standard.
- **Notes:** One product decision should cover GAP-HIST-04 / GAP-UPD-09 / GAP-STAT-04.

### GAP-UPD-10 — Absolute-date fallback English-only (shared with History)
- **Screen/surface:** UpdatesScreen — date header fallback
- **Type:** BEHAVIOR
- **OLD (target):** Native's bucket headers are fully localized string resources (`notifications_group_*`); no raw English month fallback because grouping is bucket-based (old `old:53`).
- **KMP (current):** Because Updates adopted History's calendar-date scheme, it inherits the hard-coded English `monthAbbrev` ("Jan".."Dec") for entries >6 days old (`UpdatesScreen.kt:480-494`; `kmp:73`).
- **Priority:** P2
- **Acceptance criteria:** No English month names appear under Arabic/RTL. Resolved automatically if GAP-UPD-07 restores bucket grouping; otherwise localize `monthAbbrev`.
- **Notes:** Tightly coupled to GAP-UPD-07 and GAP-HIST-07. Best fixed by the shared-helper dedup (GAP-X-01).

---

## Statistics

### GAP-STAT-01 — Overview metric formatting lost thousands-separator (`"%,d"`)
- **Screen/surface:** StatisticsScreen — overview cells + stat rows
- **Type:** VISUAL
- **OLD (target):** All integer metrics formatted with grouped thousands via `R.string.value_count` = `"%,d"` in both `OverviewItem` and `StatsItem`; read-duration via `R.string.h_m` = `"%1$dh %2$dm"` (old `StatsOverview.kt:85-90`, `StatsItem.kt:31-83`, `old:65-67,78`).
- **KMP (current):** Overview/stat values rendered as plain integer text via state fields; no evidence of `"%,d"` thousands grouping (`StatisticsScreen.kt:248-321`; metrics inventory `kmp:56-61`). `readDuration` is a VM-supplied pre-formatted "Xh Ym" string.
- **Priority:** P2
- **Acceptance criteria:** Counts ≥1000 render with locale-grouped thousands separators (e.g. "1,234") matching native; read-duration matches the "Xh Ym" format. Verify against a library with >1000 chapters.
- **Notes:** Confirm whether the KMP value text already routes through a `value_count`-equivalent formatter; if not, add one. Low effort, clear visual delta on large libraries.

### GAP-STAT-02 — Overview/stat value typography & cell layout differ
- **Screen/surface:** StatisticsScreen — overview card + stat rows
- **Type:** VISUAL
- **OLD (target):** Overview row vertical pad 16dp, item horizontal pad 18dp, value 20.sp Bold / label 12.sp; `StatsItem` value 12.sp Bold; section titles 14.sp Bold; cards `RoundedCornerShape(12.dp)` `surfaceContainerHigh`; `Row(spacedBy 8.dp)` of equal-weight cells (old `StatsOverview.kt:27-90`, `StatsItem.kt`, `old:65-67`).
- **KMP (current):** Overview cell value `headlineSmall`/SemiBold, label `labelMedium`; section title `titleMedium`/SemiBold; `StatsItem` title `bodyMedium`, value `titleMedium`/Medium; cards `surfaceVariant`; `Row(SpaceEvenly)` (`StatisticsScreen.kt:228-321`).
- **Priority:** P2
- **Acceptance criteria:** Typography scale, font weights, card color (`surfaceContainerHigh` vs `surfaceVariant`), and cell spacing/arrangement match native — or the rework type-scale tokens are accepted as the design-system standard.
- **Notes:** Multiple small deltas; treat as one visual-parity pass on `StatsOverview`/`StatsItem`. `surfaceVariant` vs `surfaceContainerHigh` is the most visible.

### GAP-STAT-03 — Statistics loading state doesn't reuse shared component
- **Screen/surface:** StatisticsScreen — loading state
- **Type:** REFACTOR
- **OLD (target):** No loading state at all — metrics are `Eagerly` started with 0/"0h 0m" defaults; screen always renders (old `StatisticsViewModel.kt:17-43`, `old:69`).
- **KMP (current):** Hand-rolled centered `CircularProgressIndicator` with early `return@Scaffold`; does not use shared `YamiLoadingState` (`StatisticsScreen.kt:177-186`; cluster note `kmp:70`).
- **Priority:** P3
- **Acceptance criteria:** Statistics either renders immediately with defaults (native behavior) or, if a loading gate is kept, uses the shared `YamiLoadingState` for consistency.
- **Notes:** Minor. KMP-EXTRA loading gate is harmless; the only parity item is shared-component reuse. Pairs with GAP-UPD-08.

### GAP-STAT-04 — Stat icons & back arrow replaced by text (no leading metric icons)
- **Screen/surface:** StatisticsScreen — stat rows + nav icon
- **Type:** DEVIATION(intentional design-system)
- **OLD (target):** Each `StatsItem` has a 24dp leading outlined Material icon (`LibraryBooks`/`NotStarted`/`DoneAll`/`SelectAll`/`RemoveRedEye`/`FileDownloadDone`/`BookmarkAdd`) tinted `onBackground`; back nav is an `AutoMirrored.ArrowBack` `IconButton` (old `StatisticsScreen.kt:52-122`, `old:63-64,66`).
- **KMP (current):** No leading icons on stat rows; back is a labelled "Back" `TextButton`; `:ui` omits `materialIconsExtended` (`StatisticsScreen.kt:165-173,299-321`; inventory `kmp:62`).
- **Priority:** P2
- **Acceptance criteria:** Same cluster-wide icon decision (GAP-HIST-04/GAP-UPD-09). If parity is required, restore the 7 leading stat icons + back arrow via `YamiIcons`; otherwise accept text posture.
- **Notes:** Statistics loses the most visual richness from icon removal (7 distinct glyphs). Weigh this when making the cluster-wide icon decision.

### GAP-STAT-05 — No charts (native also has none — confirm scope)
- **Screen/surface:** StatisticsScreen — charts
- **Type:** (no gap — scope confirmation)
- **OLD (target):** Native has NO graphical charts despite the audit prompt's "every chart" wording — purely a numeric overview card + two grouped metric lists, 8 metrics (old `old:77,100`).
- **KMP (current):** Also no charts — text-only numeric rows, identical metric set of 8 (`StatisticsScreen.kt`; metrics inventory `kmp:56-62`).
- **Priority:** P3
- **Acceptance criteria:** No action — metric set (8 fields), sections, and chart-absence all match native. Logged only to close out the "charts" line of the audit prompt: there is nothing to port.
- **Notes:** Metric parity is COMPLETE (in-library, read duration, completed, started, total, read, downloaded, bookmarked). Read-duration remains DataStore-backed via the session timer in both apps (rework session-timer port #232).

---

## Cross-cluster

### GAP-X-01 — Duplicated date-helper code across History & Updates
- **Screen/surface:** History + Updates — date grouping/formatting helpers
- **Type:** REFACTOR
- **OLD (target):** Native History and Updates use deliberately DIFFERENT schemes (History calendar-date with relative-vs-absolute fallback; Updates fixed recency buckets) — no shared helper expected (old `old:101`).
- **KMP (current):** History and Updates each carry an identical copy-pasted `groupByDate`/`formatGroupLabel`/`formatRelativeDate`/`monthAbbrev`, differing only in the date field used (`HistoryScreen.kt:325-376`, `UpdatesScreen.kt:443-494`; cluster note `kmp:72`).
- **Priority:** P3
- **Acceptance criteria:** Date helpers live in one shared `:ui` location, with localized month names (resolving GAP-HIST-07/GAP-UPD-10). NOTE: if GAP-UPD-07 restores native's distinct Updates bucket scheme, the two will legitimately diverge again — sequence this AFTER the GAP-UPD-07 decision.
- **Notes:** The KMP KDoc claims a "convergence point" that is actually copy-paste (`kmp:72`). Resolve only after deciding GAP-UPD-07.

### GAP-X-02 — Dead `*ReworkScreenRoute` duplicate adapters compiled but unwired
- **Screen/surface:** All three — route layer
- **Type:** REFACTOR
- **OLD (target):** Native has one route per screen (`HistoryRoute`/`NotificationsRoute`/`StatisticsRoute`) (old `old:14,33,60`).
- **KMP (current):** Each screen has BOTH a swapped legacy-key adapter and a parallel `*ReworkScreenRoute` rendering the same `:ui` screen; the Rework keys are unreachable in production nav — dead-but-compiled debug entries carrying large `@Suppress("UNUSED_PARAMETER")` + §253 KDoc blocks (`App.kt:651-696`; cluster note `kmp:74`).
- **Priority:** P3
- **Acceptance criteria:** Remove the unwired `*ReworkScreenRoute` duplicates (or document why they must remain), eliminating dead routes and unused-parameter suppressions once the cutover is confirmed final.
- **Notes:** Pure cleanup; no user-facing effect. Confirm the platform cutover (#422, marked COMPLETE in MEMORY) truly retired the need for the Rework keys before deleting.
