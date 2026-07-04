package me.manga.kira.presentation.features.download.data

enum class DownloadingState {
    QUEUED,
    RUNNING,
    SUCCESS,
    FAILED,
    COMPRESSING,

    // Appended (background-downloads M2). "All pages on disk, awaiting finalization (CBZ +
    // bookkeeping)." Produced only by the iOS background-URLSession engine when transfers complete
    // while the app is suspended; finalization flips it to SUCCESS on next foreground. The Room
    // TypeConverter is valueOf-based, so this new constant is read/written on every platform with no
    // migration. Append-only for on-disk stability (never reorder existing constants).
    DOWNLOADED,
}

/*
 * Audit-trail postscript (Phase 9.x.cluster207.staleKdocSweep.cascade, Task #663, 2026-05-29)
 * --------------------------------------------------------------------------------------------
 * Cluster207 leaf 2/5 — :shared/download/data/ tier closer, sibling 375. CLOSES download/data/
 * 2-of-2 with sibling 374 (DownloadState.kt). Cumulative §253-postscript count = 100 leaves with
 * this commit — CENTENARY MILESTONE.
 *
 * File-shape note: 9-line enum class — 5 variants (QUEUED, RUNNING, SUCCESS, FAILED,
 * COMPRESSING). Zero imports, zero block-KDoc — pure bare-Kotlin enum.
 *
 * Body-level deltas (cluster57+ taxonomy):
 *
 *   • LIVE-NOT-STALE — heavily-consumed wire-format SOURCE — direct consumers (verified via
 *     14-hit grep filtered to the legacy symbol):
 *       1. ChapterDownloadEntity.kt (:shared/.../data/local/entity/) — Room entity column type;
 *          persisted to disk as TypeConverted string.
 *       2. DownloadingStateConverter.kt (:shared/.../data/local/converter/) — Room TypeConverter
 *          bridging the enum to the persisted string column (name-string roundtrip).
 *       3. Converters.kt (:shared/.../data/local/converter/) — Room @TypeConverter facade aggregator.
 *       4. ChapterDownloadDao.kt (:shared/.../data/local/dao/) — DAO operations parameterize on
 *          DownloadingState for status-filtered queries (e.g. "all RUNNING downloads").
 *       5. MangaDatabase.kt + MangaDatabaseFactory.kt (:shared/.../data/local/) — Room database
 *          registers the converter; schema-aware ofDownloadingState.
 *       6. HandelDataClasses.kt (:shared/.../core/util/data_classes/) — exposed via downstream
 *          composite data classes carrying ChapterDownload + DownloadingState pairs.
 *       7. DownloadsMappers.kt (:data/mapper/) — translates legacy DownloadingState → rework
 *          :domain DownloadState via name-bridge mapping (the FQN grep confirmed both shapes
 *          coexist; :data is the rework strangler-fig boundary).
 *       8. ChapterDownloadService.kt (:shared/androidMain/.../download/domain/) — Android-only
 *          foreground service mutates entity rows through QUEUED → RUNNING → COMPRESSING →
 *          (SUCCESS | FAILED) state machine.
 *       9. DownloadRepositoryImpl.kt (:shared/androidMain/.../download/domain/clean/) — Android
 *          legacy repo facade routes DownloadingState writes.
 *      10. CoroutineDownloadRepositoryImpl.kt (:shared/nonAndroidMain/.../download/domain/clean/) —
 *          iOS/Desktop coroutine-based repo facade — emits the same 5-state lifecycle on a
 *          coroutine driver instead of WorkManager. Cross-platform parity.
 *      11. DownloadWorkerV2.kt (:shared/androidMain/.../download/ui/test2/) — Android-only
 *          WorkManager driver mapping its lifecycle to DownloadingState transitions.
 *
 *   • PERSISTENCE-WIRE-COMPAT — DownloadingState is persisted as the enum `name` string
 *     (DownloadingStateConverter does `value.name` → `String` and `enumValueOf<DownloadingState>(
 *     stored)` on read). DO NOT renumber/reorder/rename variants — existing installs persist
 *     the name string ("QUEUED", "RUNNING", "SUCCESS", "FAILED", "COMPRESSING") in Room rows and
 *     crash-on-decode if renamed without a migration shim. Same wire-compat invariant as
 *     ReadingMode (sibling 370).
 *
 *   • INVERTED-PARALLEL — rework counterpart at `:domain/model/downloads/DownloadState.kt`
 *     (cluster136) uses a DIFFERENT shape — a SEALED hierarchy (Queued / Running / Succeeded /
 *     Failed / Cancelled) rather than a flat enum. The legacy 5-variant enum is preserved here
 *     because Room's TypeConverter machinery requires a flat enum (or string serializer) for
 *     column persistence — sealed classes do not roundtrip without bespoke discriminator-column
 *     handling. The bridge happens in DownloadsMappers.kt by name-matching variants where
 *     possible (COMPRESSING has no rework counterpart — folded into Running with a "compressing"
 *     sub-status flag in the rework Page model).
 *
 *   • CROSS-PACKAGE-DEPENDENCY-LIVE — zero imports. Pure same-package enum.
 *
 *   • FIVE-STATE-COVERAGE-INVARIANT — the 5 variants map the full WorkManager lifecycle:
 *     QUEUED (work enqueued, awaiting executor) → RUNNING (pages downloading) → COMPRESSING
 *     (CBZ packaging) → SUCCESS (terminal happy path) | FAILED (terminal sad path). DO NOT add
 *     a CANCELLED variant during cleanup — legacy WorkManager treats user-cancellation as
 *     FAILED with a special error code, and the entity-side schema has no cancelled column.
 *     The rework :domain sealed DownloadState DOES carry a Cancelled variant (different
 *     orchestrator semantics — Coroutine-cancellation-aware).
 *
 * Cross-cluster :shared/download/data/ subdirectory closer status:
 *
 *   • download/data/ tier is FULLY SWEPT post-this-commit (2-of-2 files: DownloadState +
 *     DownloadingState). Remaining download/ subtree (download/domain/ + download/ui/test2/)
 *     contains androidMain/nonAndroidMain platform-leaf files that are NOT commonMain
 *     prose-bearing — out of scope for the cluster57+ commonMain §253 sweep. The download/
 *     feature subdir is thus considered FULLY SWEPT for commonMain prose-bearing audit purposes.
 *
 *   • Naming-axis posture across cluster207 leaves 1+2 (download/data/):
 *       - DownloadState (sibling 374) — INVERTED-PARALLEL: 4-state sealed (legacy lifecycle) vs
 *         5-state sealed (rework orchestrator).
 *       - DownloadingState (sibling 375 — this leaf) — INVERTED-PARALLEL with PERSISTENCE-WIRE-
 *         COMPAT pin: 5-variant flat enum (Room-persisted) vs 5-variant sealed (rework :domain).
 *     Both leaves carry INVERTED-PARALLEL postures because the rework orchestrator (coroutine-
 *     based, cross-platform) replaces the legacy orchestrator (WorkManager + foreground-service,
 *     Android-only) — different control planes drive different state shapes.
 */
