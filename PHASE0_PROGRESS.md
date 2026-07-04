# Rework — Session Containment & Phase 0 Progress

**Branch:** `architecture-rework` · **As of:** 2026-05-29 · **HEAD:** `f9c649e`
**Companion docs:** `STALE_KDOC_CASCADE_CLOSURE.md` (cascade), `ARCHITECTURE.md` (rework contract, current).
**Stale (do not trust):** `README.md` (says branch `kmp-migration`) and `migration/final-coverage-audit.md` / `migration/pending-work.md` describe the *pre-rework* graph and call it "COMPLETE" — they are about the OLD migration, not this rework.

---

## 1. What landed this session

### A. §253 audit-trail cascade — CLOSED
Clusters 263–286 (24 commits, `38f32c1` → `647765a`). Swept the final 75 zero-postscript leaves (45 `:platform` facade actuals, `:ui` ReaderDecoderHints fan, `:shared/commonMain` legacy, and the previously-unreached `:app` + `:desktopApp` host modules). Saturation proven: **764/764 .kt across all 10 app modules carry a postscript, 0 unswept** (excludes the deliberately-frozen `sources_repositry`). Full details in `STALE_KDOC_CASCADE_CLOSURE.md`.

### B. Verified current-state audit
10 read-only Opus 4.8 agents (7 inspectors + 3 adversaries). Every "done" claim re-grepped independently. Findings are in §2 below.

### C. Phase 0 safety-net (post-audit remaining-work plan)
The audit found **zero tests and zero CI** — the two hard-contract (§13/§15) gaps. Started closing them:
- `8be1164` — `:domain` `commonTest` harness (`FakeLibraryRepository`) + contract tests (enum wire-formats, `ReadingMode.isPaged`, `LibraryDisplay` defaults, `PageDownloadProgress`).
- `0814d59` — `LibraryManga` test-factory + library use-case tests (toggle add/remove branch, bulk-remove short-circuit, delegations).
- `f9c649e` — first CI workflow (`.github/workflows/ci.yml`): 6-target compile matrix + `:domain` tests. **Authored, not yet runner-executed (no push).**
- **15 tests, all green on `:domain:desktopTest`.** No production code changed in any test slice.

---

## 2. Verified current state (audit, adversary-upheld)

**Done & confirmed:**
- 15 user surfaces route-swapped to the rework graph: Library, MangaDetails, Statistics, History, Updates, Sources/RepoSettings, Theme, About, WhatsNew, Language, Complaint, AdminComplaint, Downloads, Settings, Welcome.
- Legacy retirements real — repo-wide import grep for the deleted symbols returns **zero dangling refs**.
- All 3 entry points (`:app` MyApp, iOS `bootstrapIosKoin`, `:desktopApp` Main) thread `allReworkModules()` into Koin.
- 6-target compile matrix GREEN at HEAD (composeApp Android/iOS Arm64/iOS Sim/Desktop + `:desktopApp` JVM + `:app` debug `--offline`). Verified real (not cached-stale) via compile-artifact timestamps.

**Still LEGACY at runtime (unported):**
- **Home + in-app Search** — `Screen.Home` renders legacy `HomeScreen`/`SearchScreen`. Largest gap; a primary bottom-nav tab. No rework slice exists. (XL)
- **Reader split-brain** — rework Reader is reached ONLY from rework Details (`Screen.ChapterImagesRework`); Home/History/Updates chapter-taps still hit the **legacy** reader (`Screen.ChapterImagesFragment`). Same user gets two readers by path. (L) — coupled to #217.
- **WebView** — `Screen.WebView` renders legacy `WebViewComposeScreen` (shared infra, both graphs use it). (L)

**Platform-facade cutover — NOT STARTED:** the entire `:platform` module (107 files, Phase 5.z) is unbound shadow — `0` imports of `me.manga.kira.platform.*` outside `:platform`, and `:platform` isn't even a Gradle dependency of any app. Runtime still binds the legacy `:shared` `core.*` facades. This is the substance of blocked #422.

---

## 3. Blocked — need a user decision

- **#422 — `:platform` cutover / coreshadow retire.** Legacy `:shared core.*` facades are LIVE; `:platform` is unbound shadow.
  - *Path A:* build `platformReworkModule`, migrate consumers (incl. `LibraryRefreshRepositoryImpl` → legacy `BackgroundJobScheduler`), retire legacy. ~20–30 commits.
  - *Path B:* delete `:platform` (107 files), keep legacy. 1–3 commits; abandons Phase 5.z.
  - Not auto-resolvable (changes which impl runs + compile risk). `DeviceTier` differs (top-level `expect fun`, consumed by `OptimizedCbzManager`) — pin it separately.
- **#217 — Reader chapter-bookmark.** Un-started + design-blocked on chapter identity: rework `Chapter` is `url`-keyed; legacy bookmark store keys on a Room `Long chapterId` that only exists once a manga is in-library.
  - *Path A:* net-new url-keyed store (clean, but won't feed the Library `bookmarkedCount` badge → silent divergence).
  - *Path B:* strangler-fig over legacy `ChapterDao.toggleChapterBookmark` (preserves badge; only works for in-library manga; couples `:data` to the `:shared` store).

---

## 4. Test harness (how to extend)

- **Location:** `domain/src/commonTest/kotlin/me/manga/yamiapk/domain/…`. Toolchain (`kotlin-test` + `kotlinx-coroutines-test` + `turbine`) is already wired in every module's `commonTest` — no Gradle changes needed.
- **Local gate:** `gradlew.bat :domain:desktopTest --offline`. Use `--offline` (clean/online resolve can fail on AGP `8.13.0`). iOS test targets compile `commonTest` but need a Mac to RUN; `desktopTest` is local truth.
- **Helpers (`…/domain/testing/`):** `FakeLibraryRepository` (StateFlow-backed, records `calls`); `LibraryMangaFactory` (`sampleManga`/`sampleLibraryManga`, all defaulted).
- **Instant:** construct via `Instant.fromEpochMilliseconds(...)`, **no `@OptIn`** (mirrors `data/.../LibraryMappers.kt:69`; only `Clock.System.now()` is experimental).
- **Lesson:** assert against source, not KDoc prose (`LibrarySort` has 6 values though one KDoc says "8").

---

## 5. Remaining-work plan

**Phase 0 — safety net (in progress, additive/zero-runtime-risk):**
- ✅ `:domain` tests (slices 1, 1b) · ✅ CI scaffold (slice 2)
- ⏸️ detekt + ktlint — deferred: their Gradle plugins are uncached and offline resolution is unavailable; wire + baseline in a network-capable session.
- ⬜ Slice 3 — `:presentation` `LibraryViewModel.applyView` tests (richest behavioral net; scoped — needs `Dispatchers.setMain` + fakes for `LibraryPrefsRepository`/`LibraryRefreshRepository`/`DownloadsRepository` to build the 25-dep VM).
- ⬜ Slice 4 — hygiene: delete the audit-confirmed orphan `library_sheet` trio (`CustomFilterBottomSheet`/`FilterChipsRow`/`SortOptionsSection`) + 5 dead `Screen.*Rework` route keys; refresh stale README/audit docs.

**Beyond Phase 0 (from the audit plan, after the safety net):**
1. Resolve #217 → converge the Reader (route-swap History/Updates/Home chapter-taps to the rework reader, retire the legacy reader — mirrors the `mangadetails.swap`/`retire` pattern).
2. Port Home + Search to the rework graph (XL).
3. Port-or-defer WebView.
4. Execute the #422 decision (Path A or B).

---

## 6. Build/test caveats

- "GREEN" = **compiles** (compileKotlin* tasks), not assembles/links. iOS framework link/device, Android packaging, and KSP/Room codegen were NOT exercised; iOS link needs a Mac.
- Build greenness rests on a **warm Gradle cache**. A clean checkout / CI runner doing `--refresh-dependencies` FAILED to resolve AGP `8.13.0` — a fresh build may not work until repository availability is fixed. The CI's first real run may surface this (intended signal).
- **Correction:** the online `:app` failure is AGP plugin resolution, NOT a "Unity-Ads" error — there is no Unity dependency (only a vestigial `-dontwarn com.unity3d.**` proguard line).

---

## 7. Phase 0 — FINAL STATUS (2026-05-29)

**Phase 0 (safety net) is substantially complete.** Commits this run, all on `architecture-rework`:

| Slice | Commit | Result |
|---|---|---|
| 1 — `:domain` test harness + contract tests | `8be1164` | ✅ `:domain:desktopTest` green |
| 1b — LibraryManga factory + library use-case tests | `0814d59` | ✅ green |
| 2 — CI compile-matrix workflow | `f9c649e` | ✅ authored (runner-unverified) |
| 3 — `:presentation` `LibraryViewModel.applyView` tests (12) | `0ff717d` | ✅ `:presentation:desktopTest` green |
| 4a — delete orphan `library_sheet` trio | `f52cb26` | ✅ `:composeApp` matrix green |
| 4b — flag stale README + `migration/` docs | `9b0aea3` | ✅ docs-only |

Test count: **27 tests** (`:domain` 15 + `:presentation` 12), all green on JVM. No production/runtime code was changed by any test slice; the only production deletion was the verified-dead `library_sheet` trio (gate-confirmed no dangling refs).

**Deferred (NOT done, with reasons):**
- **detekt + ktlint** — their Gradle plugins are uncached and offline resolution is unavailable here; adding them unverified risks breaking the build. Wire + baseline in a network-capable session. (TODO already noted in `.github/workflows/ci.yml`.)
- **5 dead `Screen.*Rework` route keys** (`SettingsRework`, `HistoryRework`, `UpdatesRework`, `SourcesRework`, `MangaDetailsRework`) — left in place. They are harmless (compile, unreachable). Deleting them is core-nav surgery on `App.kt` + `Screen.kt` (heavy postscripts) with the indirection-undercount risk the audit flagged (the live Settings hub reaches other `*Rework` keys via a `when`→variable→`navigate(target)`); doing it safely needs full multi-file reachability tracing + a full-matrix gate. Deferred per the "do not delete anything uncertain / keep production changes minimal" rule.

**Environmental note:** the working tree carried pre-existing, externally-made edits to `app/build.gradle.kts`, `app/src/main/.../MainActivity.kt`, and an untracked `app/.../LoginValues.kt`. These were **not** part of any Phase 0 slice and were deliberately left untouched and uncommitted.

## 8. Resume pointer
Phase 0 is done bar the two network/decision-gated items above. Next, per the audit plan (after Phase 0): resolve **#217** (chapter-bookmark identity) → converge the Reader; port **Home/Search**; port-or-defer **WebView**; execute the **#422** `:platform` cutover decision. The two blocked decisions (#422, #217) need a product/architecture call. Memory notes: `project_yami_kdoc_cascade_closed`, `project_yami_rework_test_harness`.
