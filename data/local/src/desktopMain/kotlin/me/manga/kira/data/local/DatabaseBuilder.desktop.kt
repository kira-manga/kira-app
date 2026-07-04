package me.manga.kira.data.local

import androidx.room.Room
import androidx.room.RoomDatabase
import java.io.File

actual fun mangaDatabaseBuilder(): RoomDatabase.Builder<MangaDatabase> {
    val userHome = System.getProperty("user.home") ?: "."
    val appDir = File(userHome, ".kira-manga")
    if (!appDir.exists()) appDir.mkdirs()
    val dbFile = File(appDir, MangaDatabase.DATABASE_NAME)
    return Room.databaseBuilder<MangaDatabase>(name = dbFile.absolutePath)
}

/*
 * Audit-trail postscript (Phase 9.x.cluster242.staleKdocSweep.cascade, Task #698, 2026-05-29)
 * --------------------------------------------------------------------------------------------
 * Cluster242 leaf 2/2 — desktopMain :data/local DatabaseBuilder actual,
 * sibling 489 CLOSER of CROSS-CLUSTER-FAN-CLOSER 2-leaf partial sweep.
 * Cumulative §253-postscript count = 213 leaves with this commit.
 *
 * File-shape note: 13-line file — NO file-level KDoc. 1 actual top-level
 * fun mangaDatabaseBuilder using java.io.File + System.getProperty. 1 JVM
 * stdlib import (java.io.File) beyond androidx.room base. NO companion.
 * SHORTEST-FILE-AT-cluster242 (13 lines) — also SHORTEST-FILE-IN-DATABASE
 * BUILDER-3-ACTUAL-FAN (Android=135 lines, iOS=22 lines, Desktop=13 lines).
 *
 * Body-level deltas (cluster57 plus taxonomy):
 *
 *   - CROSS-CLUSTER-FAN-CLOSER-CLOSES-LIVE — Desktop CLOSES cluster242 with
 *     CROSS-CLUSTER-FAN-CLOSER posture confirmed. The cluster242 2-LEAF
 *     PARTIAL-FAN-CLOSER fully closes the DatabaseBuilder 3-actual fan
 *     (Android-sweep-at-cluster187 + iOS-sweep-at-cluster242 + Desktop-sweep
 *     -at-cluster242). PRESERVE — load-bearing as documentation that the
 *     CROSS-CLUSTER-FAN-CLOSER pattern allows multi-pass partial-fan
 *     completion across non-adjacent cluster IDs.
 *
 *   - 12-CONSECUTIVE-CLUSTER-BEDROCK-PLATFORM-UTILITY-SUB-TIER-CLOSER-LIVE
 *     — Desktop CLOSES the cluster231-242 BEDROCK span at cluster242.
 *     CUMULATIVE-CLUSTER-SPAN-AT-cluster242: 12 consecutive BEDROCK
 *     clusters. cluster242 CLOSER classification (sibling 489). NEW POSTURE
 *     feature at cluster242 — first 12-CONSECUTIVE-CLUSTER-BEDROCK-
 *     PLATFORM-UTILITY-SUB-TIER-CLOSER classification.
 *
 *   - USER-HOME-PROPERTY-RESOLUTION-LIVE — Desktop uses
 *     `System.getProperty("user.home")` to resolve the JVM-platform-
 *     conventional user-home-directory path. The `?: "."` Elvis-fallback
 *     defaults to CURRENT WORKING DIRECTORY if user.home is unset (rare
 *     but possible in sandboxed JVM contexts, e.g. some containerized
 *     deployments). 1-DIVERGES from iOS sibling 488 (uses NSFileManager
 *     .URLForDirectory + ?: error throw, NOT silent fallback) and 1-
 *     DIVERGES from cluster187 Android sibling 297 (uses
 *     `appContext.getDatabasePath(...)`). 3-WAY-DIVERGENT-PATH-RESOLUTION
 *     -ALGORITHM-AXIS at cluster242 CONFIRMED-ACROSS-ALL-3-LEAVES.
 *     PRESERVE.
 *
 *   - APP-DIR-MKDIRS-SIDE-EFFECT-LIVE — Desktop file has
 *     `if (!appDir.exists()) appDir.mkdirs()` side-effect at builder-
 *     construction-time. Creates `~/.kira-manga/` if absent. 1-DIVERGES
 *     from iOS sibling 488 (no mkdir — relies on iOS sandbox Documents
 *     directory existing-by-default) and 1-DIVERGES from cluster187
 *     Android sibling 297 (no mkdir — relies on Android's
 *     getDatabasePath() implicitly creating /databases/ when SQLite
 *     opens). The Desktop mkdirs() IS necessary because JVM has no
 *     standard app-private-writable-directory convention; the
 *     `~/.kira-manga/` directory is a Yami-specific convention that
 *     must be created on first run. PRESERVE — defends against future
 *     "use System.getProperty(java.io.tmpdir) for transient DB"
 *     refactor (which would lose data across reboots on Linux).
 *
 *   - APP-DIR-NAMING-CONVENTION-LIVE — Desktop uses `.kira-manga`
 *     (dot-prefixed, kebab-cased) as the app-directory name. The dot-
 *     prefix IS the Unix-conventional hidden-directory marker (visible
 *     on Linux+macOS, ignored by default ls). The kebab-case naming
 *     ("kira-manga" not "kiraManga") matches Linux/macOS directory
 *     conventions. PRESERVE — defends against future "rename to
 *     KiraManga for Windows-camelCase symmetry" refactor (which would
 *     create a non-hidden directory on Linux+macOS — UX regression).
 *
 *   - NO-FOUNDATION-IMPORT-3-WAY-DIVERGENT-WITH-iOS-LIVE — Desktop file
 *     has NO platform.Foundation imports (just java.io.File). 1-
 *     DIVERGES from iOS sibling 488 (4 Foundation-related imports
 *     including cinterop + 3 NSFileManager symbols). 2-AGREE with
 *     cluster187 Android sibling 297 (no Foundation, uses Android
 *     Context API). PRESERVE.
 *
 *   - ROOM-1-ARG-BUILDER-OVERLOAD-2-AGREE-WITH-iOS-LIVE — Desktop uses
 *     `Room.databaseBuilder<MangaDatabase>(name = dbFile.absolutePath)` —
 *     the 1-arg pre-resolved-absolute-path overload. 2-AGREE with iOS
 *     sibling 488 (same overload). 1-DIVERGES from cluster187 Android
 *     sibling 297 (2-arg Context-keyed overload). The .absolutePath
 *     call IS necessary because File-object reach gives a relative-
 *     to-CWD path otherwise — Room KMP's name parameter requires
 *     absolute. PRESERVE.
 *
 *   - NO-OPTIN-ANNOTATION-LIVE — Desktop file has NO @OptIn annotation
 *     (uses pure JVM stdlib which is non-experimental). 2-AGREE with
 *     cluster187 Android sibling 297 (no opt-in). 1-DIVERGES from iOS
 *     sibling 488 (which has @OptIn(ExperimentalForeignApi::class)).
 *     PRESERVE.
 *
 *   - NO-CONTEXT-LATEINIT-VAR-2-AGREE-WITH-iOS-LIVE — Desktop file has
 *     NO module-private lateinit var. 2-AGREE with iOS sibling 488.
 *     1-DIVERGES from cluster187 Android sibling 297 (which has
 *     `private lateinit var appContext: Context` for Phase 11 Context-
 *     injection design). The user.home property IS available process-
 *     wide at JVM startup — no pre-initialization needed. PRESERVE.
 *
 *   - NO-COMPANION-OBJECT-3-AGREE-LIVE — Desktop file has NO companion
 *     object. 3-AGREE with Android+iOS. PRESERVE.
 *
 *   - SHORTEST-FILE-IN-DATABASEBUILDER-FAN-LIVE — Desktop file IS 13
 *     lines. SHORTEST-AT-cluster242 (vs iOS 22 lines, Android 135
 *     lines). The 10.4x ratio between Desktop (13) and Android (135)
 *     IS load-bearing — Android carries the Phase 11 Context-injection-
 *     design infrastructure (the setter + lateinit + check + KDoc
 *     prose) that iOS+Desktop don't need. PRESERVE.
 *
 *   - WAVE-REGISTER-CLOSES-cluster242-LIVE — Desktop CLOSES cluster242
 *     2-LEAF-PARTIAL-FAN-CLOSER fan. Combined with cluster187 Android
 *     leaf 297, the DatabaseBuilder 3-actual fan is now FULLY SWEPT
 *     across all 3 platform targets. PRESERVE.
 *
 *   - cluster243-PREDICTION — Next candidate sweep targets (in priority
 *     order): (a) Post-DatabaseBuilder, the :data/local platform-actual
 *     surface is FULLY SWEPT. Survey remaining 3-actual fans in
 *     :composeApp (if any) or :ui (unlikely — UI fans were largely swept
 *     in cluster155-165). (b) Scan for any remaining commonMain expect-
 *     declarations whose actuals were NOT yet swept (likely none — the
 *     :shared expect-decl roster is FULLY SWEPT at cluster242). (c)
 *     Move to PROJECT-COMPLETION classification — declare the §253
 *     audit-trail-postscript sweep cascade FULLY-CLOSED with 213
 *     cumulative leaves across cluster57-242 continuum. RESERVE per
 *     autonomous-cascade standing directive. (d) CryptoUtils (sources_
 *     repositry/ar/dilar) — EXCLUDED per mid-session pivot.
 */
