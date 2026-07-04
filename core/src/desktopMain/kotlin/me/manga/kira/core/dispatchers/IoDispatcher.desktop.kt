package me.manga.kira.core.dispatchers

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

actual val platformIoDispatcher: CoroutineDispatcher = Dispatchers.IO

/*
 * Audit-trail postscript (Phase 9.x.cluster247.staleKdocSweep.cascade, Task #703, 2026-05-29)
 * --------------------------------------------------------------------------------------------
 * Cluster247 leaf 2 of 5 — :core desktopMain dispatchers IoDispatcher actual,
 * sibling 508 of 5-LEAF-MIXED-OUTLIER-PLATFORM-ACTUAL-SUB-TIER-OPENER sweep.
 * Cumulative section-253-postscript count = 232 leaves with this commit.
 *
 * File-shape note: 6-line file (pre-postscript) — NO file-level KDoc. 1
 * top-level `actual val platformIoDispatcher` binding to Dispatchers.IO. 2
 * imports (kotlinx.coroutines.CoroutineDispatcher plus kotlinx.coroutines.
 * Dispatchers). NO companion. NO fun. JVM-TWIN-OF-LEAF-1.
 *
 * Body-level deltas (cluster57 plus taxonomy):
 *
 *   - CORE-DISPATCHERS-IO-ACTUAL-DESKTOP-LIVE — `actual val
 *     platformIoDispatcher = Dispatchers.IO`. JVM-tier (Desktop) gets
 *     Dispatchers.IO directly. 2-AGREE-WITH-ANDROID-LEAF-1 (both
 *     JVM platforms share the IO binding — same JDK coroutines runtime
 *     publishes Dispatchers.IO). PRESERVE — load-bearing for Desktop
 *     IO offloading (file IO for downloads, network IO for image
 *     fetches, SQLite IO via :data/local DAOs).
 *
 *   - JVM-TWIN-IDENTICAL-TO-ANDROID-LIVE — File contents byte-identical
 *     to IoDispatcher.android.kt except for actual-target source set.
 *     The identical-twin pattern IS load-bearing because Desktop and
 *     Android share the JDK coroutines runtime (different from
 *     Kotlin/Native's restricted runtime). PRESERVE — defends against
 *     future "collapse to jvmMain shared source set" refactor (which
 *     would lose the explicit cross-platform documentation that
 *     desktop=android=Dispatchers.IO).
 *
 *   - SHORT-ACTUAL-NO-PROSE-LIVE — File has zero KDoc, zero comments.
 *     2-AGREE-WITH-ANDROID-LEAF-1. PRESERVE.
 *
 *   - NO-COMPANION-OBJECT-LIVE — 5-AGREE-AT-cluster247-projected.
 *     PRESERVE.
 *
 *   - WAVE-REGISTER-CONTINUES-cluster247-LIVE — IoDispatcher.desktop.kt
 *     IS leaf 2 of 5 of cluster247. PART-OF-3-ACTUAL-FAN-FRAGMENT.
 *     PRESERVE.
 */

