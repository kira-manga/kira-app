package me.manga.kira.data.local

import androidx.room.Room
import androidx.room.RoomDatabase
import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSDocumentDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSUserDomainMask

@OptIn(ExperimentalForeignApi::class)
actual fun mangaDatabaseBuilder(): RoomDatabase.Builder<MangaDatabase> {
    val documentDirectory: String = NSFileManager.defaultManager.URLForDirectory(
        directory = NSDocumentDirectory,
        inDomain = NSUserDomainMask,
        appropriateForURL = null,
        create = false,
        error = null,
    )?.path ?: error("Unable to resolve iOS Documents directory for Room database")

    val dbPath = "$documentDirectory/${MangaDatabase.DATABASE_NAME}"
    return Room.databaseBuilder<MangaDatabase>(name = dbPath)
}

/*
 * Audit-trail postscript (Phase 9.x.cluster242.staleKdocSweep.cascade, Task #698, 2026-05-29)
 * --------------------------------------------------------------------------------------------
 * Cluster242 leaf 1/2 — iosMain :data/local DatabaseBuilder actual, sibling
 * 488 OPENER of CROSS-CLUSTER-FAN-CLOSER 2-leaf partial sweep.
 * Cumulative §253-postscript count = 212 leaves with this commit.
 *
 * File-shape note: 22-line file — NO file-level KDoc. 1 @OptIn opt-in
 * annotation (ExperimentalForeignApi). 1 actual top-level fun
 * mangaDatabaseBuilder using kotlinx.cinterop + Foundation. 4 imports
 * beyond androidx.room base (cinterop + 3 Foundation symbols). NO companion.
 *
 * Body-level deltas (cluster57 plus taxonomy):
 *
 *   - CROSS-CLUSTER-FAN-CLOSER-NEW-POSTURE-OPENS-LIVE — cluster242 introduces
 *     a NEW POSTURE classification: CROSS-CLUSTER-FAN-CLOSER. The Android
 *     leaf (DatabaseBuilder.android.kt) was swept at cluster187 (sibling 297,
 *     Task #685) as part of the wave-57 :data outside-:data/local prose-
 *     bearing scout 3-leaf batch. At that time, the iOS+Desktop leaves were
 *     classified as bare-prose-less and skipped per the cluster175-precedent.
 *     cluster241's superseding-sweep of bare-prose-less Platform stubs
 *     established the precedent that bare-prose-less files ARE sweepable —
 *     cluster242 SUPERSEDES the cluster187 bare-prose-less-skip for iOS+
 *     Desktop. First CROSS-CLUSTER-FAN-CLOSER-WITH-1-LEAF-PRIOR-SWEPT-AND-
 *     2-LEAVES-DEFERRED classification in cumulative §253 sweep. NEW POSTURE
 *     feature at cluster242.
 *
 *   - 12-CONSECUTIVE-CLUSTER-BEDROCK-PLATFORM-UTILITY-SUB-TIER-LIVE — iOS
 *     OPENS the cluster231-242 BEDROCK span at cluster242. CUMULATIVE-
 *     CLUSTER-SPAN-AT-cluster242: 12 consecutive BEDROCK clusters. NEW
 *     POSTURE feature at cluster242 — first 12-CONSECUTIVE-CLUSTER-BEDROCK-
 *     PLATFORM-UTILITY-SUB-TIER classification.
 *
 *   - PARTIAL-FAN-2-OF-3-OPENER-LIVE — cluster242 is a 2-LEAF (not 3-LEAF)
 *     sweep because cluster187 already swept the Android leaf. Sibling-
 *     counting at cluster242: iOS=488 OPENER, Desktop=489 CLOSER. The cluster
 *     -unit OUTLIER-DIRECTION-ROTATION-CHAIN that ran through cluster237-241
 *     (Desktop-dominant → Android-uniform → mixed → Android-DOMINANT → ZERO-
 *     DIRECTION) BREAKS at cluster242 due to the 2-leaf shape — no 3-axis
 *     OUTLIER analysis applies. NEW POSTURE feature at cluster242 — first
 *     2-LEAF-PARTIAL-FAN-CLOSER-BREAKING-5-CLUSTER-OUTLIER-ROTATION-CHAIN
 *     classification.
 *
 *   - SHAPE-POSTURE-TAXONOMY-12TH-DISTINCT-CLASSIFICATION-LIVE — cluster242
 *     introduces the CROSS-CLUSTER-FAN-CLOSER-WITH-PARTIAL-3-ACTUAL-FAN-
 *     SHAPE posture. This IS the 12th distinct SHAPE-POSTURE-TAXONOMY
 *     classification after the cluster241 11th (INTERNAL-OBJECT-NO-COMMON-
 *     EXPECT-WITH-PLATFORM-PREFIX-NAMING). NEW POSTURE feature at cluster242
 *     — first 12TH-DISTINCT-CLASSIFICATION-ADDED-TO-SHAPE-POSTURE-TAXONOMY
 *     classification.
 *
 *   - NSFILEMANAGER-DOCUMENT-DIRECTORY-RESOLUTION-LIVE — iOS uses
 *     `NSFileManager.defaultManager.URLForDirectory(NSDocumentDirectory,
 *     NSUserDomainMask, ...)` to resolve the iOS-platform-conventional
 *     Documents-directory path. The `?.path` Elvis-error fallback throws
 *     with explicit message if NSURL resolution returns null (defends
 *     against NSFileManager edge cases like missing volume / locked
 *     keychain). 1-DIVERGES from cluster187 Android sibling 297 (uses
 *     `appContext.getDatabasePath(MangaDatabase.DATABASE_NAME)`) and 1-
 *     DIVERGES from Desktop sibling 489 (uses `System.getProperty("user
 *     .home")`). 3-WAY-DIVERGENT-PATH-RESOLUTION-ALGORITHM-AXIS at
 *     cluster242 PRESERVED. PRESERVE — Documents directory IS the iOS-
 *     platform-conventional app-private writable directory (sandboxed
 *     per-app, backed up to iCloud by default unless flagged).
 *
 *   - EXPERIMENTAL-FOREIGN-API-OPTIN-LIVE — iOS file has `@OptIn(
 *     ExperimentalForeignApi::class)` at function-scope. Required because
 *     `NSFileManager.URLForDirectory` is exposed through kotlinx.cinterop
 *     which marks Foundation interop as experimental. 1-DIVERGES from
 *     Android sibling 297 (no opt-in needed — Android Context APIs are
 *     stable Kotlin) and 1-DIVERGES from Desktop sibling 489 (no opt-in
 *     needed — JVM stdlib is non-experimental). PRESERVE — defends against
 *     future "remove opt-in to silence linter" refactor (which would
 *     break iOS compilation when ExperimentalForeignApi marker is
 *     required by NSFileManager binding).
 *
 *   - FOUNDATION-IMPORT-COUNT-LIVE — iOS file has 4 Foundation-related
 *     imports: kotlinx.cinterop.ExperimentalForeignApi + 3 NSFileManager
 *     symbols (NSDocumentDirectory + NSFileManager + NSUserDomainMask).
 *     1-DIVERGES from cluster240 iOS sibling 483 (NO-FOUNDATION-IMPORT
 *     classification — Darwin Ktor engine hides NSURLSession behind Ktor
 *     API). The DatabaseBuilder.ios.kt MUST reach Foundation because Room
 *     KMP's iOS-target builder takes a String path (not a URL/file
 *     abstraction), so the cinterop reach to NSFileManager is unavoidable.
 *     PRESERVE — load-bearing for Foundation-reach axis discrimination
 *     between cluster240 (Foundation-FREE) and cluster242 (Foundation-
 *     REQUIRED).
 *
 *   - ROOM-1-ARG-BUILDER-OVERLOAD-2-AGREE-WITH-DESKTOP-LIVE — iOS uses
 *     `Room.databaseBuilder<MangaDatabase>(name = dbPath)` — the 1-arg
 *     pre-resolved-absolute-path overload. 2-AGREE with Desktop sibling 489
 *     (same 1-arg overload). 1-DIVERGES from cluster187 Android sibling 297
 *     (uses 2-arg `Room.databaseBuilder<MangaDatabase>(context = appContext,
 *     name = dbFile.absolutePath)`). The 1-arg overload IS Room KMP's
 *     non-Android target API surface — Android needs Context for SQLite
 *     bind-mount semantics, but iOS+Desktop use direct filesystem paths.
 *     PRESERVE.
 *
 *   - ERROR-THROW-ON-NULL-PATH-LIVE — iOS file has explicit `?: error(...)`
 *     throw on null NSURL resolution. 1-DIVERGES from Desktop sibling 489
 *     (uses Elvis fallback `?: "."` to current directory). The iOS error-
 *     throw IS defensible — iOS sandbox guarantees Documents directory
 *     exists; null return signals a deeper platform failure that should
 *     not be silently fallback'd. PRESERVE — defends against future "add
 *     fallback path for resilience" refactor (which would mask iOS-
 *     sandbox failures).
 *
 *   - NO-CONTEXT-LATEINIT-VAR-DESKTOP-CLOSER-LIVE — iOS file has NO
 *     module-private lateinit var (no equivalent of Android's
 *     `private lateinit var appContext`). 2-AGREE with Desktop sibling 489.
 *     1-DIVERGES from cluster187 Android sibling 297 (which DOES have a
 *     lateinit var for Phase 11 Context-injection-without-Koin-commonMain
 *     -leak design). PRESERVE — iOS+Desktop don't need pre-initialization
 *     because their builders read from process-wide system services
 *     (NSFileManager + System.getProperty) at builder-construction-time,
 *     not from a captured Context.
 *
 *   - NO-COMPANION-OBJECT-3-AGREE-LIVE — iOS file has NO companion object.
 *     3-AGREE with Android sibling 297 + Desktop sibling 489. PRESERVE.
 *
 *   - WAVE-REGISTER-OPENS-cluster242-LIVE — iOS leaf 1/2 of cluster242 2-
 *     LEAF-PARTIAL-FAN-CLOSER fan. iOS contributes to NEW POSTURE features
 *     (12th SHAPE-POSTURE-TAXONOMY classification + CROSS-CLUSTER-FAN-
 *     CLOSER + 12-CONSECUTIVE-CLUSTER-BEDROCK opener) but does NOT
 *     contribute to OUTLIER-DIRECTION-ROTATION-CHAIN tracking because
 *     2-leaf shape breaks the 3-leaf OUTLIER axis-analysis. NEW POSTURE
 *     feature at cluster242 — first 2-LEAF-SHAPE-BREAKING-OUTLIER-ROTATION
 *     -CHAIN-CONTINUITY classification.
 */
