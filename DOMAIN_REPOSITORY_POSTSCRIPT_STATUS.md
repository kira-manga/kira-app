# :domain/repository/ tier — §253 postscript sweep status

**TIER FULLY SWEPT** at HEAD cluster141 (pending commit). Closes wave-25.

Tier survey across `domain/src/commonMain/kotlin/me/manga/yamiapk/domain/repository/` — 26 files total.

## Final count

- **Pre-wave-25 (entering 2026-05-28)**: 13 already-swept, 13 unswept.
- **Post-cluster139**: 18 swept, 8 unswept.
- **Post-cluster140**: 23 swept, 3 unswept.
- **Post-cluster141 (current)**: 26/26 SWEPT — wave-25 closed.

## Wave-25 cluster breakdown

### cluster139 — 5-leaf reader/details opening batch (Task #595, committed HEAD `e4fa588`)

Coherent thematic grouping: the 5 repository interfaces consumed by the rework Reader + Details surfaces.

14. **`MangaDetailsRepository.kt`** — 133rd sibling, opens wave-25 + opens cluster139. 2 classifications STAND.
15. **`ChapterPagesRepository.kt`** — 134th sibling. 3 classifications STAND. Notable PARTIALLY-FULFILLED-FORECAST on the `DownloadsRepository` → `ChapterPagesRepositoryImpl.isDownloaded` branch wiring.
16. **`ReadingModeRepository.kt`** — 135th sibling. 2 classifications STAND. Cross-strangler-fig `reading_mode` DataStore key.
17. **`ReadingSessionRepository.kt`** — 136th sibling. 2 classifications STAND. begin/end asymmetric suspend/non-suspend pair.
18. **`ReadProgressRepository.kt`** — 137th sibling, closes cluster139. 3 classifications STAND. Net-new persistence (NOT strangler-fig).

### cluster140 — 5-leaf middle batch (Task #596, committed HEAD `0f5323f`)

Mix of in-memory net-new, cross-strangler-fig over :shared, and Firestore-over-:shared.

19. **`PageProgressRepository.kt`** — 138th sibling, opens cluster140. 2 classifications STAND. Dual-half ISP carve (presentation `observe` only; :platform reporters `report` only) on single interface; in-memory net-new state-flow-backed persistence.
20. **`ReadingStatisticsRepository.kt`** — 139th sibling. 2 classifications STAND. Cross-strangler-fig combine-8-flows aggregate over legacy :shared StatisticsRepository.
21. **`AboutRepository.kt`** — 140th sibling. 2 classifications STAND. Single-suspend infallible getMetadata over :shared AppVersionProvider.
22. **`WhatsNewRepository.kt`** — 141st sibling. 2 classifications STAND. Highest-fan-out 4-shared-facade strangler-fig.
23. **`ComplaintListRepository.kt`** — 142nd sibling, closes cluster140. 2 classifications STAND. User-side sibling of AdminComplaintListRepository.

### cluster141 — 3-leaf closing batch (Task #597, pending commit at current HEAD)

Tier-closing trio: the library aggregate root, the admin-side complaint list, and the source-routing surface.

24. **`LibraryRepository.kt`** — 143rd sibling, opens cluster141. 3 classifications STAND. Highest-fan-in :domain interface (consumed by 20+ use cases); 8-method surface matches :data impl 1:1 since the §179 (Task #345) action-row landing.
25. **`AdminComplaintListRepository.kt`** — 144th sibling. 2 classifications STAND. Sibling of user-side ComplaintListRepository; the deferred-actions prediction is REALISED (AdminComplaintActionRepository already a swept-leaf entering wave-25).
26. **`SourcesRepository.kt`** — 145th sibling, **CLOSES cluster141 + CLOSES wave-25 + BRINGS TIER TO 26/26 FULLY SWEPT**. 2 classifications STAND. 4-method narrowed surface; load-bearing legacy `findRepoByHost` routing path (MEMORY: `project_yami_okhttp_fetcher`) intact on the legacy :shared facade.

## Already-swept-entering-wave-25 (13 files)

Swept in prior waves while in flight on adjacent tiers:

1. `LibraryPrefsRepository.kt`
2. `SettingsRepository.kt`
3. `LanguageRepository.kt`
4. `ThemeRepository.kt`
5. `UpdatesRepository.kt`
6. `HistoryRepository.kt`
7. `ComplaintActionRepository.kt`
8. `AdminComplaintActionRepository.kt`
9. `LibraryRefreshRepository.kt`
10. `FeedbackRepository.kt`
11. `DownloadsActionRepository.kt`
12. `AdultContentClassifier.kt`
13. `DownloadsRepository.kt`

## Build-gate state

All three cluster commits passed the triple-gate (Android + iosArm64 + iosSimulatorArm64) before commit. Only pre-existing UI-layer deprecation warnings surfaced — none postscript-induced.

## Sibling index continuity

The cluster57+ sweep index advanced from prior tier (`:domain/model/` closed at 132nd sibling per cluster138 commit). Wave-25 spans 133rd → 145th. Next wave-26 cycle target candidates (per Rule 3: audit-trail surfaces next-closest productive target):

- `:core` module surfaces (AppResult, AppError, dispatchers, base contracts)
- Returning to any partial-sweep tier (`:data` / `:presentation` / `:ui` cumulative postscript review)
- `:platform` module surface survey

The live status doc (`AUDIT_TRAIL_LIVE_STATUS.md` if extant) will identify the highest-leverage next cycle.
