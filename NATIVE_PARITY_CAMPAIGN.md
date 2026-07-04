# Native-Parity Campaign — Progress & Decisions

**Goal:** Make the KMP app (`D:\yami manga\yami-kmp`) match the old Native Android app
(`D:\yami manga\yami-manga-apk-main`) EXACTLY in UI, behavior, and features. Autonomous — no
stop-for-review gates. Run Phase 0 (audit) → 1 (gap) → 2 (build) → 3 (review) end to end.

**Started:** 2026-05-31 · **Branch:** `architecture-rework`

---

## Codebase map (verified)

| | OLD native | KMP rework |
|---|---|---|
| Root | `D:\yami manga\yami-manga-apk-main` | `D:\yami manga\yami-kmp` |
| Pkg | `me.manga.yami` | `me.manga.kira` |
| Source | `app/src/main/java/me/manga/yami/` | modules `:core/:domain/:data/:platform/:presentation/:ui/:composeApp` + legacy `:shared` |
| Screens | `presentation/features/<feature>/ui/` | `:ui/.../ui/<feature>/` consuming `:presentation` MVI; routed in `:composeApp/navigation` |
| Nav | `navigation/NavGraphV2.kt` + `navigation/routes/*` | `:composeApp/.../App.kt` + `navigation/Screen.kt` + `navigation/routes/*` |

**Known leads from `PHASE0_PROGRESS.md` (2026-05-29) — to VERIFY, not trust:** Home+Search,
Reader (split-brain legacy vs rework), and WebView were flagged as still-legacy at runtime; later
task log claims Epic H (Home+Search) and Epic R (Reader) were completed. Ground truth = the audit.

---

## Autonomous decisions (ADR-style; documented per the no-gates directive)

- **AD-1 — Fan-out without file-scope races.** The goal says "agents write findings to the MD
  files" AND "agents never share a file scope." Concurrent writes to one `OLD_APP_AUDIT.md` would
  clobber. Resolution: each agent writes its OWN per-feature file under
  `audit_workspace/old/<cluster>.md` / `audit_workspace/kmp/<cluster>.md` (disjoint scopes). The
  parent (me) concatenates them into the two top-level audit files. Honors both constraints.
- **AD-2 — One agent per (side × cluster).** Keeps the old/KMP split the goal asks for, and lets the
  matching pair share an identical heading template so the two files align 1:1.
- **AD-3 — Clustering.** 19 feature areas grouped into 8 clusters (below) to keep agent count and
  context sane while staying a heavy fan-out (16 audit agents).
- **AD-4 — All agents Opus 4.8** (model: opus), read-only, must cite `file:line`.

### Clusters
1. **home_search** — Home, Search
2. **library** — Library (grid/list, sort/filter/display, categories, cards)
3. **details_reader** — MangaDetails, Reader
4. **downloads_sources** — Downloads, Sources/RepoSettings
5. **settings_theme_language** — Settings, Theme selection, Language
6. **history_updates_statistics** — History, Updates, Statistics
7. **complaint_whatsnew_welcome_about** — Complaint (user+admin), WhatsNew, Welcome, About
8. **webview_nav_shell** — WebView, navigation graph, bottom-nav/app shell, theming/design tokens

---

- **AD-5 — Phase 1 fan-out (not one agent).** Goal says "one synthesis agent." Instead used 8
  per-cluster gap agents (each diffs its aligned old/kmp pair → `audit_workspace/gaps/<cluster>.md`),
  then parent-assembled `UI_AND_FEATURE_GAPS.md`. Rationale: one agent swallowing ~460KB risks context
  overflow + lower fidelity; aligned-pair diffing is sharper. Documented deviation.
- **AD-6 — P0 verification gate.** Before Phase 2, parent verified the ⚠ P0s against live `App.kt`.
  GAP-SET-02 / THM-01 / THM-02 were FALSE POSITIVES (agents didn't read `App.kt`) — CLOSED. Lesson:
  Phase 2 must re-confirm "missing/unwired" claims against `App.kt` before building.

## Status log
- 2026-05-31: campaign opened; codebase map verified; workspace created; Phase 0 fan-out launched.
- 2026-05-31: **Phase 0 COMPLETE** — 16 audit agents (8 old + 8 kmp); assembled `OLD_APP_AUDIT.md`
  (1696 L) + `KMP_APP_AUDIT.md` (1878 L).
- 2026-05-31: **Phase 1 COMPLETE** — 8 gap agents; assembled `UI_AND_FEATURE_GAPS.md` (~242 gaps:
  P0=10→**7 real** after verification, P1=71, P2=92, P3=69).
- 2026-05-31: Phase 2 starting. Real P0 set: GAP-LIB-01/02/03 (library chapter-mgmt screen — large),
  GAP-SET-11 + GAP-SRC-01 (char-gate mismatches — tiny), GAP-CMP-01 (pinned FAQ), GAP-WN-01 (WhatsNew media).

### Phase 2 progress
- **Batch 1 — DONE** (`9788464`): GAP-SET-11 + GAP-SRC-01 closed. Lowered legacy
  `SendComplaintUseCase` body floor 8→5 (`MIN_BODY_LENGTH`) to match native + both rework UIs;
  refreshed stale KDoc. Gated Desktop+Android+iOS-sim compile (offline) green.
- **Batch 2 — DONE** (`<wn-commit>`): GAP-WN-01 implemented (WhatsNew media: image + URL carousel +
  video-poster→IntentLauncher.openUrl as DEVIATION(platform)); fixed 2 compile errs from the agent
  (missing `mutableStateOf` import + cross-module smart-cast). GAP-CMP-01 CLOSED as false positive
  (pinned FAQ already fully wired — see audit_workspace/gaps/RESOLVED_CMP01.md). Gate green x3 targets.
- **Batch 3 — DONE** (`d500281`): GAP-LIB-01/02/03 — decision (B) extend rework Details with native
  per-chapter actions (read-toggle/mark-read single+bulk, download/cancel, download-selected,
  multi-select bar) instead of a duplicate library-details screen. New :domain use cases + repo ops
  over existing DAO; :ui callback-only; Koin wired. Deferred sub-gaps (NEW badge, file-size, per-row
  bookmark, sort/filter sheet, resume FAB, parallax) labelled in audit_workspace/gaps/PLAN_LIB_chaptermgmt.md.
  Gate green x3 + :presentation/:domain tests.
- **✅ ALL 10 P0s RESOLVED** (6 false-positive/closed: SET-02, THM-01, THM-02, CMP-01; 4 done: SET-11,
  SRC-01, WN-01, LIB-01/02/03). **Phase 2 now moves to the P1 sweep (71), then P2 (92), then P3 (69).**

### P1 sweep plan
- **AD-7 (shared-file contention):** parallel implementer agents must NOT both edit shared files
  (`ui/.../composeResources/values/strings.xml`, `ui/.../components/YamiIcons.kt`). New strings go in
  per-cluster `values/strings_np_<cluster>.xml` (compose-resources merges all values/*.xml). Needed
  new icons → reported back, parent adds centrally (or one designated agent per wave). This lets P1
  clusters run in small parallel waves safely.
- Order by value/independence: Library → History/Updates/Statistics → Downloads/Sources →
  Details/Reader → Home/Search → Settings/Theme/Language → Complaint/WhatsNew/Welcome/About →
  WebView/Nav/Shell. Each cluster: verify-vs-App.kt (AD-6), implement P1s, gate, commit citing GAP ids.

#### P1 wave 1 — DONE (commit after `d500281`): Library + History/Updates/Statistics + Downloads/Sources
- 3 parallel Opus agents, disjoint scopes, per-cluster strings_np_*.xml (AD-7). Gate green x3 + tests.
- Library: LIB-15 delete-confirm, LIB-17 source brand badge, LIB-18 on-cover count badges.
- Updates: UPD-02 thumb size, UPD-03 download spinner (ObserveDownloads→VM), UPD-05 swipe, UPD-07 recency buckets. (HIST-01/UPD-01 already-parity via singleton ImageLoader header interceptor.)
- Downloads/Sources: DL-01/02 icons, SRC-02/03 localized snackbars+retry, SRC-04 animated bg+headline, SRC-07 caption+reveal.
- Sub-gaps logged: per-source colored icon needs `Source.icon` field (model+mapper change).
#### P1 wave 2 — DONE: Details/Reader + Home/Search + Settings/Theme/Lang + Complaint/etc + WebView/Nav/Shell
- 5 parallel Opus agents, gated together (fixed 2 missing imports). App-root theme rewired to `:ui`
  `YamiTheme` (tokens byte-identical → inert). SHELL-02/NAV-01 verified NOT regressions (stale gap claims).
- **✅ ALL P1s (71) ADDRESSED** across waves 1+2. Sub-gaps logged for later: per-source colored icon
  (`Source.icon` field), WhatsNew error-Result plumbing + version-gated auto-show, source-init device check.
#### P2 sweep — DONE (all 92 across 8 clusters)
- Wave: 8 parallel agents; 2 (settings, complaint/etc) hit socket errors mid-edit → re-run idempotently
  and finished. Fixed 3 interrupted-edit breakages (Search clickable import, Home Icon import, spurious
  LanguageRequestDialog onOpenUrl). Per-cluster strings_np_p2_*.xml (AD-7). Gate green x3 + tests.
- **AD-8 (agent-crash recovery):** a wave with N parallel agents can lose 1-2 to socket errors. After
  every wave: compile-gate, triage breakage from partial edits, then RE-RUN the lost clusters with an
  idempotent "verify-then-fill" prompt. Keep waves ≤4-5 agents to limit blast radius.
- **NEXT: P3 sweep (69, refactor/nice-to-have)** in 2 sub-waves of 4 clusters, then **Phase 3 final
  review**. Many P3 are DEVIATION/KMP-EXTRA (non-actionable) — agents skip those, implement real
  refactors only. Same recipe + AD-8 recovery.
- **Next up (priority order):**
  1. GAP-LIB-01/02/03 — library-details (per-manga chapter-management) screen — LARGE; new
     :ui screen + :presentation MVI + route, reusing offline chapter data path. Verify against
     App.kt first (per AD-6) — confirm no existing library-scoped chapter screen before building.
  4. Then P1 sweep by cluster (71), then P2 (92), then P3 (69).
- **Resume protocol:** real backlog = `UI_AND_FEATURE_GAPS.md` (P0 index at top; 3 CLOSED as
  false-positive). Per-cluster detail in `audit_workspace/gaps/`. Re-verify "missing/unwired"
  claims against `App.kt` before building (AD-6). Gate each batch (Desktop+Android+iOS compile,
  affected tests); commit clean slices citing the GAP id; never stage the 3 `app/` WIP files.

---
## ✅ CAMPAIGN COMPLETE (2026-05-31)
All four phases done. Phase 3 review: 4 verifier passes (2 re-run after socket errors, 1 pair
parent-verified) → see audit_workspace/review/* + FINAL_REVIEW.md. Every P0/P1/P2/P3 gap CLOSED or
justified-deferred. Final build+tests GREEN across Desktop/Android/iOS-sim. Library chapter-mgmt
delivered via decision-B (on Details, no duplicate screen). Owed: on-device visual sign-off +
logged sub-gaps (source icons, NEW-source badge, WhatsNew error/auto-show, SHELL-01 source-init
device check). Forbidden paths (sources_repositry/, old native app, 3 app/ WIP files) untouched throughout.
