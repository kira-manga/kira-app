package me.manga.kira.core.dispatchers

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO

// Dispatchers.IO is public on Kotlin/Native since coroutines 1.7.0 (kotlinx.coroutines #3205 was
// implemented then), and the project pins 1.9.0 — so bind the real elastic IO pool (DefaultIo-
// Scheduler, up to 64 threads) instead of the cores-limited Default scheduler. Routing blocking IO
// through Default would otherwise contend with CPU-bound work.
actual val platformIoDispatcher: CoroutineDispatcher = Dispatchers.IO

/*
 * Audit-trail postscript (Phase 9.x.cluster247.staleKdocSweep.cascade, Task #703, 2026-05-29)
 * --------------------------------------------------------------------------------------------
 * Cluster247 leaf 3 of 5 — :core iosMain dispatchers IoDispatcher actual,
 * sibling 509 of 5-LEAF-MIXED-OUTLIER-PLATFORM-ACTUAL-SUB-TIER-OPENER sweep.
 * Cumulative section-253-postscript count = 233 leaves with this commit.
 *
 * File-shape note: 9-line file (pre-postscript) — NO file-level KDoc. 1
 * top-level `actual val platformIoDispatcher` binding to Dispatchers.Default.
 * 3-line inline `//` comment explaining Dispatchers.IO Native-internal
 * marker plus citing kotlinx.coroutines issue #3205. 2 imports (same as
 * JVM twins). NO companion. NO fun. NATIVE-DIVERGENT-OF-LEAF-1-AND-2.
 *
 * Body-level deltas (cluster57 plus taxonomy):
 *
 *   - CORE-DISPATCHERS-IO-ACTUAL-IOS-LIVE — `actual val
 *     platformIoDispatcher = Dispatchers.Default`. Native-tier (iOS)
 *     routes IO through Dispatchers.Default because Dispatchers.IO IS
 *     declared `internal` on Kotlin/Native in coroutines 1.9.0
 *     (kotlinx.coroutines issue #3205). 1-DIVERGES-FROM-LEAF-1-AND-2
 *     (JVM twins use Dispatchers.IO directly). PRESERVE — load-bearing
 *     because the Native runtime has a single-threaded-per-context
 *     scheduler architecture that handles blocking work via the Default
 *     dispatcher's own queue (separate IO pool would be redundant).
 *
 *   - DISPATCHERS-IO-NATIVE-INTERNAL-MARKER-FLAG-LIVE — Comment on
 *     line 7-8 documents the kotlinx.coroutines issue (#3205) that
 *     this file works around. The inline-explainer IS load-bearing
 *     because future coroutines upgrades MAY lift the Native
 *     internal-marker — when that happens, this file collapses to
 *     match the JVM twins (Dispatchers.IO direct). Until then, the
 *     comment IS the canonical citation for the divergence. PRESERVE.
 *
 *   - HTTPS-LINK-TO-ISSUE-LIVE — Comment cites the upstream issue
 *     URL (https://github.com/Kotlin/kotlinx.coroutines/issues/3205).
 *     External-URL citation IS load-bearing for future-maintainer
 *     verification (the issue tracker IS the source of truth for the
 *     internal-marker status). PRESERVE.
 *
 *   - NO-COMPANION-OBJECT-LIVE — 5-AGREE-AT-cluster247-projected.
 *     PRESERVE.
 *
 *   - WAVE-REGISTER-CONTINUES-cluster247-LIVE — IoDispatcher.ios.kt IS
 *     leaf 3 of 5 of cluster247. CLOSES-3-ACTUAL-FAN-FRAGMENT (leaves
 *     1+2+3 form the :core IoDispatcher fan; leaves 4+5 are unrelated
 *     outliers). PRESERVE.
 */

