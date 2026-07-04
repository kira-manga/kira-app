package me.manga.kira.core.dispatchers

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

actual val platformIoDispatcher: CoroutineDispatcher = Dispatchers.IO

/*
 * Audit-trail postscript (Phase 9.x.cluster247.staleKdocSweep.cascade, Task #703, 2026-05-29)
 * --------------------------------------------------------------------------------------------
 * Cluster247 leaf 1 of 5 — :core androidMain dispatchers IoDispatcher actual,
 * sibling 507 OPENER of 5-LEAF-MIXED-OUTLIER-PLATFORM-ACTUAL-SUB-TIER-OPENER sweep.
 * Cumulative section-253-postscript count = 231 leaves with this commit.
 *
 * File-shape note: 6-line file (pre-postscript) — NO file-level KDoc. 1
 * top-level `actual val platformIoDispatcher` binding to Dispatchers.IO. 2
 * imports (kotlinx.coroutines.CoroutineDispatcher plus kotlinx.coroutines.
 * Dispatchers). NO companion. NO fun. SHORTEST-FILE-AT-cluster247-OPENER
 * (6 lines pre-postscript).
 *
 * Body-level deltas (cluster57 plus taxonomy):
 *
 *   - MIXED-OUTLIER-PLATFORM-ACTUAL-SUB-TIER-OPENS-LIVE — cluster247 opens
 *     NEW SUB-TIER classification after cluster246 MIXED-UTILITY-AXIS-SUB-
 *     TIER closes. The 5-leaf batch spans 2 distinct outlier shapes
 *     (3-actual :core dispatcher fan plus 2 single-platform outliers).
 *     NEW POSTURE feature at cluster247.
 *
 *   - 16-CONSECUTIVE-CLUSTER-BEDROCK-SPAN-CLOSES-AT-cluster246-CONFIRMED —
 *     cluster247 LANDS-ON-OUTLIER-CLASSIFICATION as cluster246's PRE-
 *     DICTION-OPTION-C forecast. BEDROCK-SPAN cluster231-246 (16 clusters
 *     across 4 sub-tiers: PLATFORM-UTILITY plus NAVIGATION-PACKAGE-AXIS
 *     plus COMMON-COMPONENT-UI-AXIS plus MIXED-UTILITY-AXIS) STAYS-
 *     CLOSED-AT-cluster246. Span does NOT extend to cluster247.
 *
 *   - CORE-DISPATCHERS-IO-ACTUAL-ANDROID-LIVE — `actual val
 *     platformIoDispatcher = Dispatchers.IO`. JVM-tier (Android) gets
 *     Dispatchers.IO directly (publicly available on JVM coroutines
 *     runtime). 2-AGREE-WITH-DESKTOP-LEAF-2 (Desktop also uses
 *     Dispatchers.IO — both are JVM platforms). 1-DIVERGES-FROM-IOS-
 *     LEAF-3 (iOS routes IO through Dispatchers.Default since IO IS
 *     internal on Kotlin/Native per coroutines 1.9.0). PRESERVE — load-
 *     bearing for proper IO offloading on Android (database IO, file
 *     IO, network IO all need a separate thread pool from the main
 *     dispatcher).
 *
 *   - CORE-VS-SHARED-IODISPATCHER-DUAL-LIVE-FANS — :core/dispatchers/
 *     IoDispatcher fan (this file plus IoDispatcher.desktop.kt plus
 *     IoDispatcher.ios.kt plus IoDispatcher.kt expect) IS PARALLEL-LIVE
 *     to legacy :shared/.../core/concurrency/IODispatcher fan (4 files,
 *     cluster235 §253 postscripts confirmed). Both fans coexist because
 *     :core/ IS the new rework-architecture module while :shared/ IS
 *     the legacy strangler-fig variant. Different package names
 *     (`core.dispatchers` vs `core.concurrency`), different val names
 *     (`platformIoDispatcher` vs the legacy fan's binding) — PRESERVE
 *     both as part of the rework-architecture parallel-graph posture.
 *
 *   - SHORT-ACTUAL-NO-PROSE-LIVE — File has zero KDoc, zero comments
 *     (the explainer prose lives in commonMain IoDispatcher.kt expect —
 *     cluster143 §253 postscript confirmed). 1-DIVERGES-FROM-IOS-LEAF-
 *     3 (iOS leaf has a 3-line `//` comment about Dispatchers.IO being
 *     internal on Native, citing kotlinx.coroutines issue #3205).
 *     PRESERVE.
 *
 *   - NO-COMPANION-OBJECT-LIVE — 5-AGREE-AT-cluster247-projected.
 *     PRESERVE.
 *
 *   - WAVE-REGISTER-OPENS-cluster247-LIVE — IoDispatcher.android.kt IS
 *     leaf 1 of 5 of cluster247 MIXED-OUTLIER-PLATFORM-ACTUAL-SUB-TIER-
 *     OPENER batch. PART-OF-3-ACTUAL-FAN-FRAGMENT-IN-cluster247 (sibling
 *     leaves 2+3 in same fan; leaves 4+5 are unrelated single-platform
 *     outliers). PRESERVE.
 */

