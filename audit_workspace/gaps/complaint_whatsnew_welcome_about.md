## Complaint, WhatsNew, Welcome & About — gaps

42 gaps: 8 P0, 16 P1, 12 P2, 6 P3 — covering user Complaint, Admin Complaint, WhatsNew, Welcome, and About surfaces against the OLD native target.

---

## Complaint (user-side) — gaps

### GAP-CMP-01 — Pinned FAQ complaints missing from user list
- **Screen/surface:** ComplaintScreen (user Feedback Manager)
- **Type:** MISSING_FEATURE
- **OLD (target):** `getCustomTopComplaints.kt:41-74` — two localized PINNED FAQ entries (id=`R.string.admin`, userId="0", type CUSTOM, status PINNED) are ALWAYS prepended to `allComplaints = getCustomTopComplaints(context) + Success.data` (`ComplaintScreen.kt:111-113`): (1) "Content removed (18+/hentai)" w/ reason; (2) "New manga site requirements" (≥200 titles, no bot checks) w/ reason.
- **KMP (current):** No pinned/FAQ prepend. VM `loadList()` populates `state.all` purely from `ObserveUserComplaintsUseCase` (`ComplaintViewModel.kt:124-126,251-279`); audit feature-inventory lists no pinned entries (`ComplaintScreen.kt:298-344`).
- **Priority:** P0
- **Acceptance criteria:** User Complaint list always shows the 2 localized PINNED FAQ cards at the top (above remote complaints), each rendered with the PINNED StatusChip and a ClosureReasonCard; both are Reply-only (Edit/Delete hidden). FAQ content/strings match native verbatim (Arabic + en).
- **Notes:** Port `getCustomTopComplaints` into the domain/presentation layer (not :ui) so it composes with the observed list; PINNED-gating already exists in the KMP dialog (`ComplaintActionDialog.kt:302-321`), so only the data prepend + ClosureReasonCard rendering are missing. Reuse existing `ComplaintStatusChip`.

### GAP-CMP-02 — ClosureReasonCard not rendered on CLOSED/PINNED cards
- **Screen/surface:** ComplaintRow (user card) + ComplaintActionDialog preview
- **Type:** MISSING_FEATURE
- **OLD (target):** `ComplaintCard.kt:111-117` — when status is CLOSED or PINNED and metadata "reason" present, renders `ClosureReasonCard` (Card r8, color by `ClosureReasonType.getColorScheme()`, icon DONE→CheckCircle / DONE_WAIT_UPDATE→Update / PINNED→PushPin / OTHER→Info, label `closure_reason_label`, optional type chip, reason maxLines 10) (`ComplaintComponents.kt:53-124`).
- **KMP (current):** ComplaintRow renders subject/status chip/3-line body/type/timestamp only (`ComplaintScreen.kt:449-492`); no ClosureReasonCard in the row or preview-card feature inventory.
- **Priority:** P1
- **Acceptance criteria:** CLOSED and PINNED complaints with a `reason` in metadata show a closure-reason card matching native (icon by ClosureReasonType, label, optional non-OTHER type chip, reason text). Applies to user rows and the dialog preview.
- **Notes:** Needs a shared `:ui` ClosureReasonCard + `ClosureReasonType` enum/`fromString`/`getColorScheme` ported. Required by GAP-CMP-01 (pinned FAQs carry reasons).

### GAP-CMP-03 — Device/source InfoItem row (Android version + manufacturer) missing on cards
- **Screen/surface:** ComplaintRow (user card)
- **Type:** MISSING_FEATURE
- **OLD (target):** `ComplaintCard.kt:70-96` — Row SpaceBetween of `InfoItem(Android icon, apiLevelToAndroidVersion(osVersion))` + `InfoItem(PhoneAndroid icon, manufacturer)`; osVersion from `metadata["osVersion"]`, manufacturer from `metadata["manufacturer"]`.
- **KMP (current):** No device/manufacturer InfoItem row in the KMP card (`ComplaintScreen.kt:449-492` lists subject/status/body/type/timestamp only).
- **Priority:** P1
- **Acceptance criteria:** User complaint cards display Android-version (mapped via apiLevelToAndroidVersion) and manufacturer InfoItems when present in metadata, matching native icons/layout.
- **Notes:** Port `apiLevelToAndroidVersion` + shared `InfoItem`. Metadata is captured at submit time on Android; iOS/Desktop device metadata is a DEVIATION(platform) — render only what metadata exists.

### GAP-CMP-04 — Footer short-id (Monospace) missing on user cards
- **Screen/surface:** ComplaintRow (user card)
- **Type:** VISUAL
- **OLD (target):** `ComplaintCard.kt:122-139` — footer Row SpaceBetween: `formatTimestamp(createdAt)` + `feedback_id_format`(id.take(8)) in `FontFamily.Monospace`.
- **KMP (current):** Footer shows timestamp only; no short-id chip (`ComplaintScreen.kt:449-492`).
- **Priority:** P2
- **Acceptance criteria:** User card footer shows the 8-char Monospace id alongside the timestamp, matching native `feedback_id_format`.
- **Notes:** Trivial Text addition; reuse `formatComplaintTimestamp`.

### GAP-CMP-05 — Subject vs type-label ordering / 1-line vs 2-line divergence
- **Screen/surface:** ComplaintRow header
- **Type:** VISUAL
- **OLD (target):** `ComplaintCard.kt:44-65` — left column: type display-name (`titleMedium`/SemiBold, maxLines 2 ellipsis) as the PRIMARY line, then subject (`bodySmall`/onSurfaceVariant) as secondary.
- **KMP (current):** Card shows "subject-or-type" as primary `titleMedium`/SemiBold 1-line + type label as `bodySmall` secondary (`ComplaintScreen.kt:449-492`) — inverted emphasis and 1-line vs native's 2-line type.
- **Priority:** P2
- **Acceptance criteria:** Header primary line = type display-name (maxLines 2), secondary = subject, matching native typography and line caps.
- **Notes:** Confirm against native intent; the inversion changes which text leads each card.

### GAP-CMP-06 — Body maxLines 3 vs native 10
- **Screen/surface:** ComplaintRow body
- **Type:** VISUAL
- **OLD (target):** `ComplaintCard.kt:102-108` — body `bodyMedium` maxLines 10 ellipsis.
- **KMP (current):** body `bodyMedium` 3 lines ellipsis (`ComplaintScreen.kt:449-492`).
- **Priority:** P2
- **Acceptance criteria:** User card body shows up to 10 lines before ellipsis (admin row stays 3 per native `AdminComplaintCard.kt:799-816`).
- **Notes:** One-line maxLines change; do NOT also bump admin (native admin is 3).

### GAP-CMP-07 — Results-count text present but verify string/format
- **Screen/surface:** ComplaintScreen SearchAndFilterSection
- **Type:** VISUAL
- **OLD (target):** `feedbacks_found_count` results-count text (`ComplaintScreen.kt` feature inventory line 18).
- **KMP (current):** `complaint_results_count` text present (`ComplaintScreen.kt:22` inventory).
- **Priority:** P3
- **Acceptance criteria:** Count string matches native pluralization/wording (`feedbacks_found_count`), Arabic + en.
- **Notes:** Likely a key-name divergence only; confirm strings align.

### GAP-CMP-08 — Card container color (surfaceVariant) vs native elevated Card
- **Screen/surface:** ComplaintRow card style
- **Type:** VISUAL
- **OLD (target):** `ComplaintCard.kt:30-37` — `Card` elevation 2.dp, `RoundedCornerShape(16.dp)`, default surface container (elevated).
- **KMP (current):** `surfaceVariant` container, `RoundedCornerShape(12.dp)`, no stated elevation (`ComplaintScreen.kt:430-442`).
- **Priority:** P2
- **Acceptance criteria:** Decide one card visual language; if matching native, use elevated Card r16 + 2.dp elevation. Apply consistently to user + admin.
- **Notes:** KMP standardized r12/surfaceVariant across the cluster; this is a deliberate KMP design-system choice. Recommend keeping KMP's system but document as accepted DEVIATION if not aligning to r16/elev.

### GAP-CMP-09 — User mutation snackbars: KMP-EXTRA vs native silent
- **Screen/surface:** ComplaintScreen (reply/edit/delete feedback)
- **Type:** KMP-EXTRA
- **OLD (target):** Native shows NO snackbar/toast on user mutations — route wires `onShowMessage = {}`, messages dropped (`ComplaintScreenRoute.kt`; `ComplaintScreen.kt:231,237,243`; OLD cluster note line 141).
- **KMP (current):** Snackbars on reply/edit/delete success+error via `ComplaintEffect.ShowSuccessMessage/ShowErrorMessage` (`ComplaintScreen.kt:173-180`).
- **Priority:** P3
- **Acceptance criteria:** Decide keep-or-remove. Recommended KEEP — silent mutation in native is an INFERRED bug/oversight; visible confirmation is a genuine UX improvement. Document as intentional divergence.
- **Notes:** Low risk. If strict parity is mandated later, gate behind a flag rather than delete.

### GAP-CMP-10 — Edit dialog: subject now propagated (KMP fixes native bug)
- **Screen/surface:** ComplaintActionDialog EDIT
- **Type:** KMP-EXTRA
- **OLD (target):** `ComplaintActionDialog.kt:333-473` — EditContent lets user edit subject locally but `onEdit(complaint, body)` only propagates body; subject change is silently discarded (INFERRED bug, OLD note line 143).
- **KMP (current):** `OnSubmitEdit(subject, body)` propagates BOTH subject and body (`ComplaintActionDialog.kt:458-587`; `ComplaintViewModel.kt:198-229`).
- **Priority:** P3
- **Acceptance criteria:** Keep KMP behavior (subject persists). Confirm `EditComplaintUseCase` writes subject to backend.
- **Notes:** Desirable bug-fix; keep. No action beyond verification.

### GAP-CMP-11 — Reply original-complaint reference card / metadata "replyto"
- **Screen/surface:** ComplaintActionDialog REPLY
- **Type:** BEHAVIOR
- **OLD (target):** `ComplaintActionDialog.kt:230-328` — Reply shows an original-complaint reference card (label `reply_to_complaint_id`, subject 1-line, body 2-line) and builds a NEW `Complaint(status=OPEN, metadata += "replyto"->id)`.
- **KMP (current):** Reply panel highlights `complaint_replying_to` in primary on the preview card (`ComplaintActionDialog.kt:361-394`); submit is `OnSubmitReply(trimmed)` → `ReplyToComplaintUseCase` (`ComplaintViewModel.kt:198-229`). Whether it creates a new OPEN complaint with `replyto` metadata is not confirmed in audit.
- **Priority:** P1
- **Acceptance criteria:** Replying creates a new complaint with `status=OPEN` and `metadata["replyto"]=originalId` (verify the use case does this); reference card shows label + subject (1-line) + body (2-line).
- **Notes:** Open question — verify `ReplyToComplaintUseCase` payload matches native metadata shape so admin `replyToId` display (GAP-CMP-12) works.

---

## Admin Complaint — gaps

### GAP-CMP-12 — Admin row: replyToId reference + app-version chip placement
- **Screen/surface:** AdminComplaintRow
- **Type:** MISSING_FEATURE
- **OLD (target):** `AdminComplaintScreen.kt:713-768` — header column shows type, subject, optional `replyToId` text (from metadata "replyto"), then Row with `user_id_format`(userId.take(8)) Monospace + optional app-version Surface chip (tertiaryContainer, "v$ver" Monospace, r4).
- **KMP (current):** Row footer shows type label + userId.take(8) Monospace + optional `vX` appVersion + optional timestamp (`AdminComplaintScreen.kt:700-736`); no `replyToId` reference line.
- **Priority:** P1
- **Acceptance criteria:** Admin card displays the `replyto` reference (when present) and renders the app-version chip with native styling (tertiaryContainer Surface, Monospace, r4).
- **Notes:** Depends on GAP-CMP-11 metadata. App-version chip styling may differ (KMP renders inline Monospace text vs native Surface chip).

### GAP-CMP-13 — Admin row action IconButtons (closure-note / edit / delete) missing from row
- **Screen/surface:** AdminComplaintRow footer
- **Type:** MISSING_FEATURE
- **OLD (target):** `AdminComplaintScreen.kt:829-895` — footer right Row of 3 IconButtons: closure-note (secondaryContainer, Note icon), edit (tertiaryContainer, Edit icon), delete (errorContainer, Delete icon); plus status `Surface` (primaryContainer, clickable→StatusChange) containing StatusChip + Edit icon (`:770-794`).
- **KMP (current):** Row click opens admin MENU dialog (`AdminComplaintScreen.kt` interactions); all four mutations are reached via the dialog, NOT via per-row IconButtons. No in-row action buttons or clickable status surface.
- **Priority:** P1
- **Acceptance criteria:** Either (a) restore native per-row action IconButtons + clickable status surface, OR (b) accept the dialog-MENU consolidation as an intentional, documented DEVIATION. If (b), confirm all four native actions remain reachable (they are, via MENU).
- **Notes:** KMP consolidated 4 row affordances into a single row-tap→MENU. Recommend documenting as accepted simplification unless native fidelity is strict; flag for parity review.

### GAP-CMP-14 — Admin back affordance: actions-slot TextButton vs native navigationIcon
- **Screen/surface:** AdminComplaintScreen TopAppBar
- **Type:** VISUAL
- **OLD (target):** `AdminComplaintScreen.kt:162-188` — `CenterAlignedTopAppBar` with back arrow as `navigationIcon` (AutoMirrored ArrowBack) + stats-toggle IconButton in actions.
- **KMP (current):** Back is an `actions`-slot `TextButton` labeled "back", NOT a navigationIcon (`AdminComplaintScreen.kt:208-224`).
- **Priority:** P2
- **Acceptance criteria:** Admin back uses a leading `navigationIcon` arrow consistent with user-side ComplaintScreen and native; harmonize the cluster's 3 inconsistent back treatments (user=navIcon arrow, admin=actions TextButton, About=navIcon TextButton).
- **Notes:** Cross-cluster nit (KMP note line 113). Pick one back treatment; arrow `navigationIcon` matches native best.

### GAP-CMP-15 — Stats-toggle (show/hide statistics) action missing
- **Screen/surface:** AdminComplaintScreen TopAppBar + StatisticsCard
- **Type:** MISSING_FEATURE
- **OLD (target):** `AdminComplaintScreen.kt:162-188` — TopAppBar actions has a toggle-stats IconButton (`VisibilityOff`/`Visibility`); StatisticsCard renders only when `showStats`.
- **KMP (current):** StatisticsCard is always item 1 of the list (`AdminComplaintScreen.kt:323-371`); no visibility toggle.
- **Priority:** P2
- **Acceptance criteria:** Add a TopAppBar toggle that shows/hides the StatisticsCard, default-visible matching native default.
- **Notes:** Small state flag in AdminComplaintState + intent. Low risk.

### GAP-CMP-16 — Statistics card "By app version" top-5 gating
- **Screen/surface:** AdminStatisticsCard
- **Type:** BEHAVIOR
- **OLD (target):** `AdminComplaintScreen.kt:581-688` — "By app version" section only when >1 app version, showing TOP-5 by count (sorted desc); "By status" shows only statuses with count>0.
- **KMP (current):** StatisticsCard shows per-status rows + per-app-version rows (`AdminComplaintScreen.kt:556-644`); audit does not confirm top-5 cap, >1-version gate, or count>0 filtering.
- **Priority:** P2
- **Acceptance criteria:** Per-status rows shown only when count>0; app-version section appears only with >1 version and is capped to top-5 by count, descending.
- **Notes:** Verify `computeStatistics`/card rendering (`AdminComplaintViewModel.kt:258-296`). KMP-EXTRA risk: KMP stats card itself exceeds native (native VM `getComplaintsStatistics()` was unwired; native card computed inline). KMP card is fine — just match gating.

### GAP-CMP-17 — Admin no-matches missing SearchOff icon
- **Screen/surface:** AdminComplaintScreen no-results state
- **Type:** VISUAL
- **OLD (target):** `AdminComplaintScreen.kt:260-270` — no-results `EmptyState(no_results_found / try_different_filters, icon=FilterAlt)`.
- **KMP (current):** Admin no-matches is plain text only, no icon (`AdminComplaintScreen.kt:345-361`); user-side DOES show an icon (`ComplaintScreen.kt:312-334`).
- **Priority:** P2
- **Acceptance criteria:** Admin no-matches shows an icon (native uses FilterAlt; user-side uses SearchOff) + title + message, consistent with user-side.
- **Notes:** KMP note line 115. Reuse a shared `:ui` empty/no-match component with an icon slot.

### GAP-CMP-18 — Admin sort affix "▾" vs native DropdownMenu styling
- **Screen/surface:** AdminComplaintScreen sort control
- **Type:** VISUAL
- **OLD (target):** `AdminComplaintScreen.kt` sort `DropdownMenu` (7 options) via SortOption enum (OLD line 45).
- **KMP (current):** Sort `OutlinedButton`+`DropdownMenu` over 7 `AdminSortMode.entries` with a "▾" text affix (`AdminComplaintScreen.kt:518-543`).
- **Priority:** P3
- **Acceptance criteria:** Sort control uses a proper dropdown-arrow icon rather than a literal "▾" glyph; 7 modes preserved.
- **Notes:** Minor polish; both reach the same 7 sort modes (DATE_DESC default etc.).

### GAP-CMP-19 — Active-filters summary line
- **Screen/surface:** AdminComplaintScreen SearchAndFilterSection
- **Type:** MISSING_FEATURE
- **OLD (target):** Feature inventory lists an "active-filters summary line" (OLD line 46).
- **KMP (current):** Not present in KMP admin feature inventory (`AdminComplaintScreen.kt:374-516`) — only results-count text.
- **Priority:** P3
- **Acceptance criteria:** Show an active-filters summary line when any filter/search is active, matching native content.
- **Notes:** Nice-to-have; verify native renders it (inventory-only mention).

### GAP-CMP-20 — Admin bulk multi-select: KMP-EXTRA never landed (native lacked it too)
- **Screen/surface:** AdminComplaintScreen
- **Type:** KMP-EXTRA
- **OLD (target):** Native `bulkUpdateStatus`/`bulkDeleteComplaints` exist in `AdminComplaintViewModel` but are NOT wired to any UI (OLD note line 144). Task #265 "bulk" exceeds native parity.
- **KMP (current):** NO bulk-select UI; `AdminComplaintIntent` has no bulk variants (`AdminComplaintIntent.kt:104-237`; KMP note line 109). KDoc claims bulk "landed" but it is absent.
- **Priority:** P3
- **Acceptance criteria:** Resolve the stale KDoc. RECOMMEND: DROP bulk-select (native never exposed it; no parity need) and remove "landed" KDoc claims, OR implement only if product explicitly wants it.
- **Notes:** Keep/remove rec = REMOVE for parity. Either way fix the misleading KDoc.

### GAP-CMP-21 — Admin mutation snackbars: KMP-EXTRA vs native println/silent
- **Screen/surface:** AdminComplaintScreen
- **Type:** KMP-EXTRA
- **OLD (target):** Admin route `showMessage` is just `println("Admin Message: ...")` — no visible snackbar/toast (`AdminComplaintScreenRoute.kt:37-41`; OLD note line 141). Only Toast in native admin is body-copy `title_copied`.
- **KMP (current):** Real snackbars on admin mutations via `LaunchedEffect` collecting effects (`AdminComplaintScreen.kt:180-187`).
- **Priority:** P3
- **Acceptance criteria:** KEEP (visible feedback is a clear improvement over println). Document as intentional divergence.
- **Notes:** Same disposition as GAP-CMP-09.

### GAP-CMP-22 — Admin dialog PINNED override (no gate) parity
- **Screen/surface:** AdminComplaintActionDialog
- **Type:** BEHAVIOR
- **OLD (target):** Native admin can act on any complaint incl. PINNED (admin dialogs offer Status/Edit/Closure/Delete with no PINNED gate; `StatusChangeDialog.kt`).
- **KMP (current):** Admin dialog explicitly has NO PINNED gate (`AdminComplaintActionDialog.kt:118-120`) — matches native.
- **Priority:** P3
- **Acceptance criteria:** Confirmed parity; no change. (Recorded for completeness.)
- **Notes:** No action. Contrast with user dialog which correctly gates PINNED to Reply-only.

### GAP-CMP-23 — Closure-reason builds "${type.key}: ${reason}" + auto-CLOSED side effect
- **Screen/surface:** AdminComplaintActionDialog CLOSURE_REASON
- **Type:** BEHAVIOR
- **OLD (target):** `StatusChengeDialog.kt:288-456` / `AdminComplaintViewModel.kt` — closure builds `"${type.key}: ${reason}"` unless type==OTHER; `addClosureReason` writes metadata reason/reasonAddedBy/reasonAddedAt and AUTO-sets status CLOSED if currently OPEN/IN_PROGRESS. Reason-type dropdown (`ClosureReasonType.entries`) + pre-fill of existing reason/type.
- **KMP (current):** CLOSURE_REASON is a plain textarea + counter (≤500) + Add (`AdminComplaintActionDialog.kt:352-420`) → `AddClosureReasonUseCase`. NO reason-type dropdown, NO `type.key:` prefixing, pre-fill/auto-CLOSED not confirmed.
- **Priority:** P1
- **Acceptance criteria:** Closure dialog offers a ClosureReasonType dropdown, pre-fills existing reason+type, stores `"${type.key}: ${reason}"` (except OTHER), writes reasonAddedBy/reasonAddedAt metadata, and auto-sets CLOSED from OPEN/IN_PROGRESS. Verify `AddClosureReasonUseCase` does the metadata + status side effects.
- **Notes:** Largest admin-dialog functional gap. Requires porting `ClosureReasonType` + display text. Ties to GAP-CMP-02 (card rendering reads the same reason format).

### GAP-CMP-24 — Admin closure char cap 500 vs native (verify)
- **Screen/surface:** AdminComplaintActionDialog CLOSURE_REASON / EDIT
- **Type:** VISUAL
- **OLD (target):** Native closure reason field is `height 120 maxLines 5` with no explicit char counter cited; admin edit body `height 120 maxLines 5` (`StatusChangeDialog.kt:112-456`).
- **KMP (current):** Closure ≤500 char counter; edit body ≤1000 counter (`AdminComplaintActionDialog.kt:352-551`).
- **Priority:** P3
- **Acceptance criteria:** Confirm char caps match native intent (native admin edit had no explicit cap cited; KMP adds 1000). Keep counters if harmless; align caps if native enforced different limits.
- **Notes:** KMP adds validation native lacked — likely a desirable improvement; verify no backend conflict.

### GAP-CMP-25 — StatusChange: button-per-status vs native radio list
- **Screen/surface:** AdminComplaintActionDialog STATUS_CHANGE
- **Type:** VISUAL
- **OLD (target):** `StatusChangeDialog.kt:45-92` — radio list (RadioButton + StatusChip) of ALL statuses; Confirm `update_status` enabled when selection ≠ current.
- **KMP (current):** A button PER status excluding current (`AdminComplaintActionDialog.kt:286-350`) — immediate apply, no radio+confirm step.
- **Priority:** P2
- **Acceptance criteria:** Decide pattern. If matching native, use radio-select + Confirm. KMP's one-tap-applies is fewer steps; document as DEVIATION if kept.
- **Notes:** Behavioral nuance: native requires explicit Confirm; KMP applies on tap. Confirm UX intent with product.

---

## WhatsNew — gaps

### GAP-WN-01 — Feature media (images / carousels / video) entirely missing
- **Screen/surface:** WhatsNewScreen FeatureCard
- **Type:** MISSING_FEATURE
- **OLD (target):** `FeatureCard.kt:112-183` + `FeatureMedia` — per-card media: SingleImage (res), ImageCarousel (res-list), ImageUrlsCarousel (url-list via Coil `SubcomposeAsyncImage`), SingleUrlImage, VIDEO via `SafeVideoPlayer`, ImagePlaceholder/VideoPlaceholder fallbacks.
- **KMP (current):** FeatureCard renders title + optional NewChip + description ONLY; "NOT present (all deferred): images, video, fullscreen viewer" (`WhatsNewScreen.kt:338-392`, feature inventory line 70).
- **Priority:** P0
- **Acceptance criteria:** FeatureCard renders the feature's media per mediaType: single image (res/url), image carousels (res-list/url-list), and video; placeholders when absent. Uses Coil for URL images (cross-platform).
- **Notes:** Largest WhatsNew gap. Video is the hardest (cross-platform player) — consider phasing: P0 for images/carousels, video may be DEVIATION(platform) per platform if no shared player. Mitigated in practice because native default feature list is empty (see GAP-WN-04), but remote data may carry media.

### GAP-WN-02 — Fullscreen media viewer missing
- **Screen/surface:** WhatsNewScreen
- **Type:** MISSING_FEATURE
- **OLD (target):** `FullscreenMediaViewer.kt:26-141` — tap media → black .95α overlay with full image (Coil) or `SafeVideoPlayer`, close IconButton top-end, `tap_outside_to_close` caption.
- **KMP (current):** No media, so no fullscreen viewer; "No clicks, no long-press, no media" (`WhatsNewScreen.kt:261-299`).
- **Priority:** P1
- **Acceptance criteria:** Tapping a card's image/video opens a fullscreen viewer with close + tap-outside-to-dismiss, matching native.
- **Notes:** Depends on GAP-WN-01. Downgrade to P2 if media itself is deferred.

### GAP-WN-03 — Prev/Next/Get-Started navigation buttons missing
- **Screen/surface:** WhatsNewScreen NavigationButtons
- **Type:** MISSING_FEATURE
- **OLD (target):** `WhatsNewComponents.kt:98-141` — Row: `OutlinedButton` Previous (or Spacer), `Button` Next, and on last page `Button` `get_started` → onDismiss.
- **KMP (current):** Only horizontal swipe + page-indicator dots; "nav arrows" listed under NOT present (`WhatsNewScreen.kt:261-299`, inventory line 70). No Get-Started/dismiss button.
- **Priority:** P1
- **Acceptance criteria:** Show Previous/Next buttons mirroring page state and a Get-Started button on the last page that dismisses/marks-seen.
- **Notes:** Ties to GAP-WN-05 (onDismiss/markSeen). Swipe-only is reachable but native parity wants explicit buttons.

### GAP-WN-04 — Remote-data fetch + default-fallback behavior
- **Screen/surface:** WhatsNew data/behavior
- **Type:** BEHAVIOR
- **OLD (target):** `WhatsNewViewModel.kt:24-263` — fetches remote features by user-language code, maps `RemoteData`→`WhatsNewFeature` via `getLocalizedFeature`, falls back to `getDefaultFeatures` on empty/error (which currently returns EMPTY → EmptyState). Version-gated auto-show; on new version sets `ds.setNewSources(true)`.
- **KMP (current):** VM `init{}`→`loadFeatures()` via `GetWhatsNewFeaturesUseCase`; `:data` SWALLOWS failures and returns empty list (KDoc:59-67), so empty is the de-facto path; error state wired-but-dormant (`WhatsNewViewModel.kt:140-171`, KMP note line 65,119).
- **Priority:** P1
- **Acceptance criteria:** WhatsNew fetches remote, localized features (by language) and renders them when present; on error, surface the error state (don't silently swallow) OR fall back to defaults — matching native's remote-first-with-fallback contract.
- **Notes:** KMP `:data` swallowing failures diverges from native's error/empty branching. Native default list is empty by design, so empty fallback is acceptable, but error suppression should be reconsidered (the error UI exists but never fires).

### GAP-WN-05 — Version-gated auto-show + markSeen persistence not wired
- **Screen/surface:** WhatsNew auto-show gate
- **Type:** MISSING_FEATURE
- **OLD (target):** `WhatsNewViewModel.kt` — `checkIfShouldShowWhatsNew()` auto-shows when currentVersionCode>lastShown; `markWhatsNewAsSeen()` persists version+timestamp; `shouldShowBasedOnTime` (30d). onDismiss marks seen when isFirstOpen.
- **KMP (current):** `OnMarkSeen` is VM-wired to `MarkWhatsNewSeenUseCase` but NEVER dispatched by `:ui` — "auto-show gate deferred" (`WhatsNewViewModel.kt:140-171`, KMP note line 119).
- **Priority:** P1
- **Acceptance criteria:** WhatsNew auto-shows on version bump (and/or 30-day rule), and dismissing/Get-Started marks it seen (persists version+timestamp) so it doesn't reappear. `setNewSources(true)` side effect preserved if used by NewChip/sources badge.
- **Notes:** Dispatch `OnMarkSeen` from a Get-Started/dismiss action (GAP-WN-03). Wire the version-gate at the route or app-start level. Open question: where the auto-show trigger should live in KMP nav.

### GAP-WN-06 — NewChip: KMP-EXTRA vs native (isNew/version unused)
- **Screen/surface:** WhatsNewScreen FeatureCard
- **Type:** KMP-EXTRA
- **OLD (target):** `FeatureCard.kt:28-110` — OLD renders NO NewChip/isNew badge nor version label; `feature.isNew`/`feature.version` are unused in UI (OLD note line 97,145). Task #248 NewChip is net-new.
- **KMP (current):** Renders a primary-color `NewChip` (`new_badge` text) next to title when feature.isNew (`WhatsNewScreen.kt:338-392`).
- **Priority:** P3
- **Acceptance criteria:** Decide keep-or-remove. RECOMMEND KEEP (harmless enhancement; uses existing model field) but note it is non-parity; ensure remote/default data populates `isNew` meaningfully or the chip never shows.
- **Notes:** Native target lacked it. Keep is low-risk; if strict parity, remove the chip + `new_badge` string.

### GAP-WN-07 — Header close (X) button + title bar parity
- **Screen/surface:** WhatsNewScreen header
- **Type:** VISUAL
- **OLD (target):** `WhatsNewComponents.kt:21-63` — header Surface (tonalElev 2), Row SpaceBetween: title `whats_new_title` headlineSmall Bold + close IconButton (40.dp circle surfaceVariant .5α, `Close` icon).
- **KMP (current):** `TopAppBar` title `what_s_new`, NO back/close icon — relies on system back (`WhatsNewScreen.kt:195-199`).
- **Priority:** P2
- **Acceptance criteria:** Provide an explicit close affordance (X) consistent with native; title styling (`whats_new_title`, Bold) matches.
- **Notes:** On Desktop/iOS there is no hardware back, so an explicit close is functionally important — bumps real-world priority. String key divergence (`what_s_new` vs `whats_new_title`) to reconcile.

### GAP-WN-08 — Gradient background + responsive sizing missing
- **Screen/surface:** WhatsNewScreen container
- **Type:** VISUAL
- **OLD (target):** `WhatsNewScreen.kt:53-64` — full-screen vertical gradient (primaryContainer .3α → surface); responsive isTablet/isLandscape padding + media sizing; card `fillMaxHeight(0.85/0.75)`, r20, elev4; FeatureCard slide+fade `AnimatedVisibility` 250ms.
- **KMP (current):** `Scaffold` plain background; pager card = `surfaceVariant` r12, padding lg (`WhatsNewScreen.kt:261-370`); no gradient, no responsive sizing, no card slide/fade animation.
- **Priority:** P2
- **Acceptance criteria:** Apply the primaryContainer→surface vertical gradient and the per-card slide+fade animation; consider responsive card height. Card corner/elevation to match (r20/elev4) or accept design-system r12.
- **Notes:** Visual polish; pair with GAP-CMP-08 card-style decision for cluster consistency.

### GAP-WN-09 — PageIndicators pill geometry (24×8 selected) vs KMP dots
- **Screen/surface:** WhatsNewScreen PageIndicatorRow
- **Type:** VISUAL
- **OLD (target):** `WhatsNewComponents.kt:65-96` — selected pill 24×8 primary, unselected 8×8 outline .3α, r4 (elongated pill).
- **KMP (current):** dot row — selected 10.dp primary, unselected 8.dp onSurfaceVariant .3α (round dots) (`WhatsNewScreen.kt:307-330`).
- **Priority:** P3
- **Acceptance criteria:** Selected indicator is an elongated 24×8 pill (r4) matching native, not a 10.dp dot.
- **Notes:** Minor; same color intent, different shape.

---

## Welcome — gaps

### GAP-WEL-01 — Lottie animated background replaced by gradient (DEVIATION)
- **Screen/surface:** WelcomeScreen background
- **Type:** DEVIATION(platform)
- **OLD (target):** `WelcomeScreen.kt:118-132` — `AnimatedBackground` = Lottie raw resource `R.raw.background`, looping forever, `ContentScale.FillBounds`.
- **KMP (current):** NO Lottie — lottie-compose not on `:ui` classpath; `background.lottie` asset unused; substituted by a Compose-native `rememberInfiniteTransition` panning gradient (primary→secondary→base, 9000ms) (`WelcomeScreen.kt:124-154`, KMP note line 107). Task #743 only landed the About-logo half.
- **Priority:** P2
- **Acceptance criteria:** Either (a) add a multiplatform Lottie renderer (e.g. Compottie) to `:ui` and render `background.lottie` to match native, OR (b) formally accept the gradient as an intentional cross-platform DEVIATION and update task #743/KDoc to reflect it.
- **Notes:** Gradient "works" and is not broken; this is a fidelity gap, not a functional one. Compottie supports KMP if exact Lottie parity is required. Decision needed.

### GAP-WEL-02 — Welcome title/subtitle/CTA visual parity (verify)
- **Screen/surface:** WelcomeScreen content
- **Type:** VISUAL
- **OLD (target):** `WelcomeScreen.kt:42-116` — title `welcome_title` headlineLarge 36sp ExtraBold primary; subtitle `welcome_suptitle` bodyMedium 18sp onBackground .9α; Get-Started `Button` fillMaxWidth h52 r26 primary, label 18sp onPrimary; bottom-aligned Column padding 24 animateContentSize 600ms.
- **KMP (current):** Matches: title 36sp ExtraBold primary, subtitle 18sp .9α, Button fillMaxWidth h52 r26, label 18sp, Column padding 24 animateContentSize 600ms (`WelcomeScreen.kt:178-218`).
- **Priority:** P3
- **Acceptance criteria:** Confirmed visual parity for text/CTA; no change needed beyond background (GAP-WEL-01).
- **Notes:** Recorded as verified parity. Only the legibility overlay + background differ.

---

## About — gaps

### GAP-ABT-01 — Discord social button is a no-op (no URL) — parity but broken UX
- **Screen/surface:** AboutScreen SocialMediaRow
- **Type:** BEHAVIOR
- **OLD (target):** `SocialMediaRow.kt:33-164` — Discord uses `CustomIcons.Discord` with a no-op default callback (native also defaults to no-op unless overridden) — i.e. native Discord is ALSO inert by default.
- **KMP (current):** Discord button is present-but-no-op tap target; "Discord has no URL (no-op)" (`AboutScreen.kt:488-493`, KMP note line 119).
- **Priority:** P1
- **Acceptance criteria:** Provide the real Discord invite URL and wire the button to open it (the watch item explicitly flags "Discord URL"). If no canonical invite exists, hide the Discord button rather than ship a dead control.
- **Notes:** Both OLD and KMP ship Discord inert, so this is parity — but the task brief calls out Discord URL as a watch item, implying the intended behavior is a working invite (native had `openDiscordInvite` helper in `openLink.kt`). Open question: confirm canonical invite URL.

### GAP-ABT-02 — "Source code" row omitted (native had inert "soon" row)
- **Screen/surface:** AboutScreen items group
- **Type:** MISSING_FEATURE
- **OLD (target):** `AboutScreen.kt:108-149` — includes a "Source code" row (`soon` subtitle, `Icons.Outlined.Code`, inert/disabled).
- **KMP (current):** Source-code row intentionally omitted (`AboutScreen.kt` feature inventory line 94: "NOT present: Source-code row — intentionally omitted, disabled in legacy").
- **Priority:** P3
- **Acceptance criteria:** Decide: restore the inert "Source code / soon" row for visual parity, OR confirm intentional omission. RECOMMEND restore for 1:1 row parity (it's a cheap disabled row) unless product wants it gone.
- **Notes:** Native row was inert anyway; omission is low-impact. Documented KMP choice.

### GAP-ABT-03 — About logo size 120.dp vs native 250.dp
- **Screen/surface:** AboutScreen header logo
- **Type:** VISUAL
- **OLD (target):** `AboutScreen.kt:99-107` — `Image(R.drawable.ic_launcher_foreground)` 250.dp, vertical padding 24.
- **KMP (current):** logo `ic_launcher_foreground` 120.dp centered (`AboutScreen.kt:253-259`).
- **Priority:** P2
- **Acceptance criteria:** About logo renders at native 250.dp (or a deliberate responsive size), padding 24 — currently less than half native size.
- **Notes:** KMP KDoc wrongly says logo "dropped" though it renders (KMP note line 105). Fix size + correct the stale KDoc.

### GAP-ABT-04 — Header divider + ItemsGroup divider styling
- **Screen/surface:** AboutScreen body
- **Type:** VISUAL
- **OLD (target):** `AboutScreen.kt:108-149` — `Divider` (Gray .3α) under logo + Spacer 24; rows separated by Dividers (background-color .8α).
- **KMP (current):** Rows card with dividers `background` alpha 0.8 (`AboutScreen.kt:364-369`); a separate under-logo Gray .3α divider is not confirmed.
- **Priority:** P3
- **Acceptance criteria:** Under-logo divider (Gray .3α) present; inter-row dividers at .8α — match native.
- **Notes:** Minor; KMP uses a card-wrapped ItemsGroup (M3) vs native Material2 ItemsGroup — see GAP-ABT-06.

### GAP-ABT-05 — SocialMediaRow press-scale spring animation
- **Screen/surface:** AboutScreen social buttons
- **Type:** VISUAL
- **OLD (target):** `SocialMediaRow.kt:33-164` — each `SocialMediaButton` has scale-press spring animation (0.85f) on tap; circle primaryContainer .2–.3α; adaptive 36–56.dp button / 18–28.dp icon; SpaceEvenly.
- **KMP (current):** Social buttons = `IconButton` in primaryContainer-tinted circle, adaptive 36–56.dp / 18–28.dp (`AboutScreen.kt:444-542`); no press-scale spring noted.
- **Priority:** P3
- **Acceptance criteria:** Social buttons animate a 0.85f press-scale spring on tap matching native; sizing/alpha already aligned.
- **Notes:** Use `animateFloatAsState` on interaction-source pressed. Reuse android-compose-ui press-scale pattern.

### GAP-ABT-06 — About uses M3 vs native Material2 Scaffold/TopAppBar (REFACTOR — desirable)
- **Screen/surface:** AboutScreen scaffold
- **Type:** REFACTOR
- **OLD (target):** `AboutScreen.kt:70-84` — native About uses Material2 `Scaffold`/`TopAppBarCom`/`Divider`/`IconButton` (mixed-version, OLD note line 147).
- **KMP (current):** M3 `Scaffold` + `TopAppBar` (`AboutScreen.kt:223-232`).
- **Priority:** P3
- **Acceptance criteria:** Keep M3 (KMP correctly standardizes the cluster on M3; native's M2 in About was a legacy inconsistency). No revert.
- **Notes:** Desirable divergence; recorded so it isn't re-flagged as a regression.

### GAP-ABT-07 — Package-id footer label: KMP-EXTRA
- **Screen/surface:** AboutScreen footer
- **Type:** KMP-EXTRA
- **OLD (target):** Native About has no package-id footer label (feature inventory line 134 lists only logo/version/rows/social).
- **KMP (current):** `PackageLabel` footer (labelSmall centered onSurfaceVariant, package id) (`AboutScreen.kt:376-388`).
- **Priority:** P3
- **Acceptance criteria:** Decide keep-or-remove. RECOMMEND KEEP (harmless, informative) but note non-parity.
- **Notes:** Native showed versionName in the Version row only; KMP adds package id at bottom.

### GAP-ABT-08 — Social row button count / ordering parity
- **Screen/surface:** AboutScreen SocialMediaRow
- **Type:** VISUAL
- **OLD (target):** `SocialMediaRow.kt:33-164` — 6 buttons in order: X, Facebook, Instagram, WhatsApp, Discord, Website; WhatsApp prefilled message to `01558657735`.
- **KMP (current):** 6-button row X/Facebook/Instagram/WhatsApp/Discord-noop/Website (`AboutScreen.kt:444-542`) — order matches.
- **Priority:** P3
- **Acceptance criteria:** Confirm 6 buttons in native order; WhatsApp opens with the prefilled message + Egypt 0→20 normalization. (Discord covered by GAP-ABT-01.)
- **Notes:** Mostly parity; verify WhatsApp prefilled-message + phone normalization survived the port.

---

## Cross-cluster gaps

### GAP-CMP-26 — Shared LoadingState / ErrorState visual parity
- **Screen/surface:** Complaint + Admin + WhatsNew loading/error
- **Type:** VISUAL
- **OLD (target):** `LoadingState.kt:27-91` (rotating Refresh 64.dp 1000ms + message + LinearProgressIndicator); `ErrorState.kt:27-122` (errorContainer card r16, code-specific icon WifiOff/Lock/CloudOff/Error, `error_code_format` Monospace, Retry button).
- **KMP (current):** Complaint loading = plain centered `CircularProgressIndicator` (`ComplaintScreen.kt:230-232`); error = error-color text + Retry TextButton (`:269-289`). WhatsNew uses `YamiLoadingState`/`YamiErrorState`.
- **Priority:** P2
- **Acceptance criteria:** Loading/error states match native richness (animated loading indicator + message; error card with code-specific icon + error code + Retry). Use a shared `:ui` component (YamiStateViews) across the cluster.
- **Notes:** KMP already has `YamiLoadingState`/`YamiErrorState`/`YamiEmptyState` (memory: design-system). Apply them to Complaint screens too for consistency.

### GAP-CMP-27 — EmptyState icon + title + message vs KMP plain text
- **Screen/surface:** Complaint + Admin empty states
- **Type:** VISUAL
- **OLD (target):** `EmptyState.kt:26-65` — centered icon (Inbox 80.dp .6α) + title `titleLarge`/Medium + message `bodyMedium` (defaults `no_feedback_found`/`no_feedback_message`).
- **KMP (current):** User empty = centered `complaint_no_feedback` TEXT only (`ComplaintScreen.kt:240-247`); admin empty = `admincomplaint_no_complaints` text (`AdminComplaintScreen.kt:244-251`) — no icon/title.
- **Priority:** P2
- **Acceptance criteria:** Empty states render icon + title + message matching native; reuse shared `YamiEmptyState`.
- **Notes:** Pairs with GAP-CMP-17 (no-match icon). One shared component fixes both.

---

## Summary of dispositions
- **KEEP (KMP-EXTRA, document as intentional):** user snackbars (CMP-09), admin snackbars (CMP-21), subject-edit fix (CMP-10), NewChip (WN-06, ensure data-driven), package footer (ABT-07), M3 About (ABT-06).
- **REMOVE / resolve stale KDoc:** admin bulk-select (CMP-20).
- **DECIDE (platform/product):** Welcome Lottie vs gradient (WEL-01), admin per-row actions vs MENU (CMP-13), StatusChange radio vs one-tap (CMP-25), card visual language r12/surfaceVariant vs native r16/elevated (CMP-08).
- **VERIFY use-case payloads:** reply `replyto` metadata (CMP-11), closure `type.key:` + auto-CLOSED + metadata (CMP-23), WhatsApp prefill/normalization (ABT-08).
