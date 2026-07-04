# Phase-3 REVIEW — Complaint/WhatsNew/Welcome/About + WebView/Nav/Shell/Theming

Adversarial read-only verification against LIVE committed code. Status legend: CLOSED (gap fully resolved in live code), DEFERRED(reason) (intentionally not done / accepted deviation, documented), STILL-OPEN (real residual gap), N/A-CONFIRMED (parity verified, no action was needed).

---

## Cluster A — Complaint (user-side)

| GAP-ID | status | evidence (file:line) | note |
|---|---|---|---|
| CMP-01 | CLOSED | data/.../PinnedComplaints.kt:92-124; ComplaintListRepositoryImpl.kt:130-133 | `PINNED_COMPLAINTS` (2 entries id="admin", PINNED, CUSTOM, each w/ `reason`) prepended in `loadUserComplaints()`. ClosureReasonCard renders via reason. ComplaintScreen.kt KDoc L86 ("intentionally NOT ported") is STALE but PinnedComplaints postscript documents true LIVE state. |
| CMP-02 | CLOSED | ui/.../complaint/ClosureReason.kt:53-180; ComplaintScreen.kt:469-478 | `ClosureReasonType` enum + `fromString`/`colorScheme`/icon ported; `ClosureReasonCard` rendered on CLOSED/PINNED rows when reason present. Icon-by-type + label + non-OTHER chip + maxLines 10 all match native. |
| CMP-03 | DEFERRED(platform) | ComplaintSummary lacks osVersion/manufacturer | Device InfoItem row not rendered; metadata is Android-only at submit-time. Gap itself flagged iOS/Desktop as DEVIATION(platform). P1 visual, not load-bearing. |
| CMP-04 | CLOSED | ComplaintScreen.kt:479-508 | Footer Row: timestamp + 8-char Monospace short-id (`np_feedback_id_format`); null createdAt → Spacer; empty id omits chip. |
| CMP-05 | CLOSED | ComplaintScreen.kt:425-452 | Type display-name PRIMARY (titleMedium SemiBold maxLines 2); subject secondary (bodySmall). |
| CMP-06 | CLOSED | ComplaintScreen.kt:453-460 | User body bodyMedium maxLines 10; admin row stays 3 (AdminComplaintScreen.kt:745-750). |
| CMP-07 | CLOSED | ComplaintScreen.kt:391-395 | Results-count Text present (`complaint_results_count`). String-key divergence only; acceptable. |
| CMP-08 | CLOSED | ComplaintScreen.kt:413-419; Admin:697-703 | Elevated Card 2.dp + r16 across user+admin+stats — aligned to native. |
| CMP-09 | DEFERRED(KEEP) | ComplaintScreen.kt:175-182 | User mutation snackbars kept as intentional UX improvement (native silent). Per gap disposition. |
| CMP-10 | CLOSED | ComplaintViewModel.kt:209-218; ComplaintActionDialog EDIT | `OnSubmitEdit(subject, body)` propagates BOTH; editComplaint writes subject. KMP bug-fix kept. |
| CMP-11 | CLOSED | ComplaintActionRepositoryImpl.kt:80-96; ComplaintActionDialog.kt:340-394 | Reply builds new `LegacyComplaint(status=OPEN, metadata=mapOf("replyto" to parent.id))`; dialog ref card = label + subject(1-line) + body(2-line). |

## Cluster A — Complaint (admin-side)

| GAP-ID | status | evidence (file:line) | note |
|---|---|---|---|
| CMP-12 | CLOSED | AdminComplaintScreen.kt:735-789 | `replyToId` reference line (primary) + app-version chip (tertiaryContainer Surface, Monospace, r4). `replyToId` threaded by ComplaintListRepositoryImpl.kt:145. |
| CMP-13 | DEFERRED(accepted) | AdminComplaintActionDialog.kt MENU | Per-row IconButtons consolidated into row-tap→MENU; all 4 mutations reachable. Documented simplification per gap option (b). |
| CMP-14 | CLOSED | AdminComplaintScreen.kt:231-240 | Back is leading `navigationIcon` arrow (was actions TextButton); harmonized with user-side. |
| CMP-15 | CLOSED | AdminComplaintScreen.kt:241-260, 343-345 | `OnToggleStatsCard` + Visibility/VisibilityOff IconButton; StatisticsCard gated on `state.showStats`. |
| CMP-16 | CLOSED | AdminComplaintScreen.kt:619-681 | By-status rows gated count>0; By-app-version shown only when >1 version, top-5 sortedByDescending. |
| CMP-17 | CLOSED | AdminComplaintScreen.kt:373-382 | Admin no-matches shows `ComplaintSearchOff` icon (shared w/ user-side). |
| CMP-18 | DEFERRED(P3 nit) | AdminComplaintScreen sort dropdown | "▾" glyph vs dropdown-arrow icon; 7 modes preserved. Minor polish, not verified changed. |
| CMP-19 | DEFERRED(P3) | — | Active-filters summary line; nice-to-have, inventory-only native mention. Not landed. |
| CMP-20 | DEFERRED(KEEP-bulk) | AdminComplaintIntent bulk variants | Bulk slice (task #265) exists in intents/VM; native lacked UI. KDoc no longer claims absent. Resolved either way. |
| CMP-21 | DEFERRED(KEEP) | AdminComplaintScreen.kt:180-187 | Admin mutation snackbars kept over native println. Per gap disposition. |
| CMP-22 | N/A-CONFIRMED | AdminComplaintActionDialog (no PINNED gate) | Admin acts on any status incl PINNED — matches native. |
| CMP-23 | CLOSED | AdminComplaintActionDialog.kt:387-549; AdminComplaintActionRepositoryImpl.kt:136-156 | Dialog: ClosureReasonType dropdown + pre-fill + `"${key}: ${reason}"` build (except OTHER) + strip-prefix-on-edit. Repo: writes reason/reasonAddedBy/reasonAddedAt, auto-CLOSED from OPEN/IN_PROGRESS. Full native parity. |
| CMP-24 | DEFERRED(KEEP) | AdminComplaintActionDialog.kt:394, 558 | Closure ≤500 / edit ≤1000 char counters kept (validation native lacked). |
| CMP-25 | CLOSED | AdminComplaintActionDialog.kt:304-385 | RadioButton + StatusChip list over all statuses; Confirm enabled only when selection ≠ current. Replaced one-tap-applies. |

## Cluster A — WhatsNew

| GAP-ID | status | evidence (file:line) | note |
|---|---|---|---|
| WN-01 | CLOSED | WhatsNewScreen.kt:636-796 | `FeatureMedia` branches mediaType: single URL image / URL carousel / video poster; Coil `AsyncImage` IS now a :ui dep (import L66). KDoc L129-141 ("No image rendering / Coil isn't a :ui dependency") is STALE — contradicted by live render. |
| WN-02 | CLOSED | WhatsNewScreen.kt:476-528 | `FullscreenMediaViewer` Dialog: black .95α + Coil image + close IconButton + tap_outside_to_close caption. |
| WN-03 | CLOSED | WhatsNewScreen.kt:441-474 | Previous (Spacer on first) / Next / Get-Started (last page) NavigationButtons. |
| WN-04 | DEFERRED(fallback-not-surface) | WhatsNewViewModel.kt:160-171; GetWhatsNewFeaturesUseCase.kt:77; WhatsNewRepositoryImpl.kt:138 | Implements native remote-first-WITH-fallback (onFailure → defaults/empty). Error UI exists but is DORMANT — `errorMessage` never written; UC returns bare List, not Result. The "surface error don't swallow" alternative was NOT done. Task #756 ("WN-04 error surfacing") overstates: surfacing is dormant, fallback is live. Gap's OR-criteria satisfied via fallback branch. |
| WN-05 | CLOSED | WhatsNewScreen.kt:272-308; WhatsNewViewModel.kt:154 | `OnMarkSeen` now DISPATCHED from close-X + Get-Started (was never dispatched); VM calls `MarkWhatsNewSeenUseCase`. NOTE: version-gated AUTO-show trigger at app-start still FORECAST-NOT-FULFILLED (deferred per VM KDoc). |
| WN-06 | DEFERRED(KEEP) | WhatsNewScreen.kt:803-818 | NewChip kept (data-driven on isNew). Per gap disposition. |
| WN-07 | CLOSED | WhatsNewScreen.kt:258-285 | Bold title + explicit close (X) IconButton in actions; transparent topbar. |
| WN-08 | CLOSED | WhatsNewScreen.kt:243-256, 390-405, 576-585 | primaryContainer→surface vertical gradient; per-card fade+slide 250ms AnimatedVisibility; card r20 + elev4. |
| WN-09 | CLOSED | WhatsNewScreen.kt:537-561 | Selected indicator elongated 24×8 pill r4; unselected 8×8 low-alpha. |

## Cluster A — Welcome & About

| GAP-ID | status | evidence (file:line) | note |
|---|---|---|---|
| WEL-01 | DEFERRED(accepted DEVIATION) | WelcomeScreen.kt:124-168 | Lottie → Compose-native panning gradient (lottie not on :ui classpath); asset preserved. Accepted cross-platform deviation. |
| WEL-02 | N/A-CONFIRMED | WelcomeScreen.kt:178-218 | title 36sp ExtraBold primary; subtitle 18sp .9α; Button fillMaxWidth h52 r26 18sp; padding 24 animateContentSize 600ms. Verbatim parity. |
| ABT-01 | DEFERRED(no canonical invite) | AboutScreen.kt:509-515 | Discord button still NO-OP (`onClick = { /* no Discord landing page */ }`). Parity-with-native (native also inert); no Discord URL constant exists. Task #755 title ("Discord invite URL wired") is MISLEADING — no URL was wired. Gap watch-item intent (working invite) unfulfilled. |
| ABT-02 | CLOSED | AboutScreen.kt:301-308 | Inert "Source code / soon" row restored (onClick=null). |
| ABT-03 | CLOSED | AboutScreen.kt:260-268 | Logo 250.dp + vertical padding 24 (was 120.dp). |
| ABT-04 | CLOSED | AboutScreen.kt:270-272, 386-391 | Under-logo HorizontalDivider Gray .3α; inter-row dividers background .8α. |
| ABT-05 | CLOSED | AboutScreen.kt:531-549 | Social buttons 0.85f press-scale spring via interactionSource + animateFloatAsState + graphicsLayer. |
| ABT-06 | N/A-CONFIRMED | AboutScreen.kt:230-238 | M3 Scaffold/TopAppBar kept (desirable divergence). No revert. |
| ABT-07 | DEFERRED(KEEP) | AboutScreen.kt:323, 398-410 | PackageLabel footer kept (harmless). Per gap disposition. |
| ABT-08 | CLOSED | AboutScreen.kt:466-525 | 6 buttons X/Facebook/Instagram/WhatsApp/Discord/Website in native order; WhatsApp URL prefilled w/ phone=201558657735 (Egypt-normalized) + text. |

---

## Cluster B — WebView

| GAP-ID | status | evidence (file:line) | note |
|---|---|---|---|
| WV-01 | CLOSED | core/webview/WebViewUrlSandbox.kt:27-58; WebViewComposeScreen.kt:179,299; WebViewHost.android.kt:64-76 | `WebViewUrlSandbox.isAllowed` ports legacy rule set (about:/data: allowed, main-frame same-host http/https, sub-frame javascript/file blocked, parse-fail→allow). Wired via `allowNavigation` → Android `shouldOverrideUrlLoading`. |
| WV-02 | DEFERRED(behavior-watch) | WebViewHost.android.kt:83-90 | Cookie capture is `onPageFinished`-driven (CookieManager.getCookie) vs legacy per-request `shouldInterceptRequest`. Deliberate substitute; behavior-parity watch item per gap. |
| WV-03 | DEFERRED(platform) | WebViewHost.android.kt:117-123 | onRenderProcessGone recovery/recreation not ported; dispose does stopLoading+destroy (graceful teardown — minimum bar met). Android-only failure mode. |
| WV-04 | DEFERRED(accepted) | WebViewHost.android.kt:174-180 | Native back/forward list via controller goBack/goForward (cross-platform, more-correct model) vs legacy 50-entry in-memory history. Gap calls the divergence acceptable. |
| WV-05 | CLOSED | WebViewHost.android.kt:57-61 | setSupportZoom(true) + builtInZoomControls=true + displayZoomControls=false. iOS WKWebView zoom on by default. |
| WV-06 | CLOSED (security half) | WebViewHost.android.kt:43-55 | Security posture restored: allowFileAccess=false, allowContentAccess=false, file-URL access off, mixedContent COMPATIBILITY, mediaPlaybackRequiresUserGesture=true, geolocation off. ANR cacheMode/hardware-layer/focus-guards (DEVIATION/optional) not ported. |
| WV-07 | DEFERRED(platform) | WebViewHost.android.kt:117-123 | pauseTimers/resumeTimers lifecycle not wired (Android-only, low impact for modal browser). |
| WV-08 | DEFERRED(platform) | WebViewHost.android.kt:119-122 | cleanupWebView reduced to stopLoading+destroy (leak risk covered). clearCache/clearHistory optional. |
| WV-09 | CLOSED | WebViewComposeScreen.kt:199,206,225,234,243,252 | All chrome strings via `stringResource` (close/loading/back/forward/reload/save_headers). Localization invariant restored. |
| WV-10 | DEFERRED(platform) | WebViewHost.desktop.kt | Desktop JCEF per-browser UA override limitation; documented. |
| WV-11 | N/A-CONFIRMED | core/webview/WebViewController.kt | Controller abstraction is a healthy KMP improvement; nav controls at parity. |

## Cluster B — Navigation

| GAP-ID | status | evidence (file:line) | note |
|---|---|---|---|
| NAV-01 | DEFERRED(not-a-regression — consolidation) | App.kt:478-502 | LibraryMangaDetails retired; library item → Screen.MangaDetails → MangaDetailsByUrlReworkScreenRoute. Per memory `dae40c0` the rework url route resolves library items from local DB offline → route consolidation, not a network-regression. **Verdict holds.** Residual: on-device offline confirmation still owed (no device in env). |
| NAV-02 | DEFERRED(dev-scaffold) | App.kt *Rework keys | Parallel debug routes; cleanup item to gate behind debug flag pre-release. |
| NAV-03 | DEFERRED(P3 cleanup) | navigation/safePopBackStack.kt | `[ReaderNav]` println to remove pre-release; clearFocus drop accepted (commonMain). |
| NAV-04 | DEFERRED(platform) | navigation/safePopBackStack.kt:8-34 | Android focus-clear guard dropped in commonMain; 3-catch fallback-to-library preserved (load-bearing part). |

## Cluster B — Shell

| GAP-ID | status | evidence | note |
|---|---|---|---|
| SHELL-01 | **STILL-OPEN (source-init) / DEFERRED (Google SPIs)** | grep `initializeSources` → ZERO live-code hits (only .md docs + OLD app) | `updateSources.initializeSources()` NOT found anywhere in live KMP production code. Real functional risk: sources may not auto-refresh at startup. Gap flagged this exact split (source-init = functional P1; ads/update/review/FCM/analytics = platform deviations). The Google-only effects are legitimately descoped/SPI; **the source-init is an unresolved P1 — needs a startup home or explicit descope note.** |
| SHELL-02 | DEFERRED(not-a-regression — refactor) | App.kt:439, 504-517 | SharedChaptersViewModel eliminated; rework Reader (R4/R5 convergence) fetches its own chapter list via :data. Memory parity-campaign-complete + reader-convergence confirm in-reader prev/next works. **Verdict holds** (clean refactor). Residual: per-entry-point on-device confirmation owed. |
| SHELL-03 | DEFERRED(P2) | — | deleted-manga / random-empty guard Toasts not enumerated in rework; tie to SHELL-02 re-home. Localized copy (fix typos). Not verified landed. |
| SHELL-04 | N/A-CONFIRMED | App.kt:266-291, 306-341 | CoilSourceHeaderInterceptor + singleton ImageLoader (AVIF Android, OkHttp forced Android, maxBitmapSize(Size.ORIGINAL)). `[CoilDbg]` printlns removed. Memory invariants hold. |

## Cluster B — Theming

| GAP-ID | status | evidence (file:line) | note |
|---|---|---|---|
| THEME-01 | CLOSED | App.kt:17 (import ui.theme.YamiTheme), :341 (call); theme/Theme.kt:23-36 | App root binds rework :ui `YamiTheme(darkTheme, pureBlack)` directly. Legacy `YamiMangaTheme` reduced to a thin alias delegating to YamiTheme (matches gap acceptance "removed OR reduced to a thin alias"). No double-wrap; LocalSpacing provided at root. **App-root rewire holds.** |
| THEME-02 | DEFERRED(platform) | YamiTheme.kt; theme/Theme.kt:20-21 | dynamicColor param forwarded but no-op (no DynamicColorProvider SPI). OFF-by-default both sides → no parity gap. Pure forecast. |
| THEME-03 | DEFERRED(P3 cleanup) | :composeApp composeResources/font/ | Unreferenced Gilroy/Poppins/alba/extra-Gellix fonts still shipped (parity-neutral; OLD also shipped dead). Bundle-size cleanup. |

---

## Residual open items
1. **SHELL-01 source-init — STILL-OPEN (functional P1).** `updateSources.initializeSources()` has no live-code home in KMP; sources may not auto-refresh on startup. Either wire it via an Android startup SPI / host onCreate, or explicitly document as descoped. (No device in env to confirm runtime behavior.)
2. **WN-04 error-surfacing — DEFERRED, dormant.** Error UI exists but never fires; only the fallback-to-defaults branch is live. Acceptable per native contract, but the "don't silently swallow" path was not built. Task #756 wording overstates completion.
3. **ABT-01 Discord — DEFERRED.** Button still inert (no canonical invite URL); task #755 title overstates ("wired"). Parity preserved; watch-item intent (working invite) unfulfilled.
4. **CMP-03 device InfoItem — DEFERRED(platform).** Not rendered (Android-only metadata).
5. **WN-05 auto-show gate — partial.** markSeen now dispatched (CLOSED); version-gated auto-show trigger at app-start still forecast-not-fulfilled.
6. **On-device sign-off owed** for NAV-01 (offline library details) and SHELL-02 (in-reader prev/next per entry point) — verdicts hold by code+memory, but no AVD/device in this env.
7. **Stale KDoc (cosmetic, non-blocking):** ComplaintScreen.kt:86 ("pinned FAQ NOT ported") and WhatsNewScreen.kt:129-141 ("No image rendering / Coil not a :ui dep") both contradict live code; postscripts elsewhere document true state.

## Per-cluster verdicts
- **Complaint (user+admin): SOLID.** All 8 P0/P1 functional gaps (CMP-01/02/11/12/13/16/23/25) verified CLOSED in live code; reply-`replyto` + closure type-prefix/auto-CLOSED/metadata use-case payloads confirmed correct. Remaining items are accepted KEEP-deviations and P2/P3 nits.
- **WhatsNew: SOLID with one caveat.** WN-01 media + WN-02 fullscreen + WN-03 nav buttons + WN-05 markSeen + WN-07/08/09 all CLOSED; WN-04 is fallback-not-surface (dormant error UI) and auto-show gate still deferred.
- **Welcome/About: SOLID.** ABT-02/03/04/05/08 CLOSED; WEL-01 accepted deviation; ABT-01 Discord remains inert (parity, watch-item unfulfilled).
- **WebView: SOLID.** WV-01 sandbox + WV-05 zoom + WV-06 security + WV-09 strings CLOSED and wired to Android actual; remaining WV items are documented platform deviations.
- **Nav/Shell/Theming: MOSTLY SOLID — one real open item.** THEME-01 app-root YamiTheme rewire CLOSED; GAP-NAV-01 & SHELL-02 "not-a-regression" verdicts HOLD (route consolidation + reader self-fetch). **SHELL-01 source-init is the one genuine STILL-OPEN functional gap.**
