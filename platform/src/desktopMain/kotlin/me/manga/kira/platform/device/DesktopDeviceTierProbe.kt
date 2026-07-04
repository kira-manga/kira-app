package me.manga.kira.platform.device

import co.touchlab.kermit.Logger
import java.lang.management.ManagementFactory
import me.manga.kira.core.util.heap.DeviceTier
import me.manga.kira.core.util.heap.classifyByTotalRam

/**
 * Desktop actual for [DeviceTierProbe].
 *
 * Preferred path: cast `OperatingSystemMXBean` to `com.sun.management.OperatingSystemMXBean` via
 * reflection and call `getTotalPhysicalMemorySize()`. The reflective indirection means this code
 * stays compileable on JVMs that strip the `com.sun.management` surface, and keeps building when
 * `--add-opens` rules tighten in future Java releases.
 *
 * Fallback: when the sun-bean isn't available (or the reflective probe fails for any reason),
 * use `Runtime.maxMemory()` × 4 as a heuristic — desktop JVMs default to roughly 25% of host RAM.
 * For unbounded heaps (`Long.MAX_VALUE`) classify directly as [DeviceTier.MID] rather than
 * multiplying into overflow.
 *
 * Verbatim semantic port from legacy `:shared/desktopMain/.../core/util/heap/DeviceTier.desktop.kt`.
 */
class DesktopDeviceTierProbe : DeviceTierProbe {

    private val log = Logger.withTag(TAG)

    override fun detect(): DeviceTier {
        sunBeanTotalPhysicalMemory()?.let { return classifyByTotalRam(it) }
        val maxJvm = Runtime.getRuntime().maxMemory()
        if (maxJvm == Long.MAX_VALUE) return DeviceTier.MID
        return classifyByTotalRam(maxJvm * HEAP_TO_RAM_HEURISTIC_FACTOR)
    }

    private fun sunBeanTotalPhysicalMemory(): Long? = runCatching {
        val bean = ManagementFactory.getOperatingSystemMXBean()
        val sunBeanClass = Class.forName(SUN_BEAN_CLASS)
        if (!sunBeanClass.isInstance(bean)) return@runCatching null
        val method = sunBeanClass.getMethod(SUN_BEAN_METHOD)
        method.invoke(bean) as? Long
    }.onFailure { e ->
        log.d(e) { "$SUN_BEAN_CLASS unavailable; falling back to heuristic" }
    }.getOrNull()

    private companion object {
        const val TAG = "DeviceTierProbe.desktop"
        const val SUN_BEAN_CLASS = "com.sun.management.OperatingSystemMXBean"
        const val SUN_BEAN_METHOD = "getTotalPhysicalMemorySize"
        const val HEAP_TO_RAM_HEURISTIC_FACTOR = 4L
    }
}

/*
 * §253 audit-trail postscript — cluster268 §253 sweep (2026-05-29)
 * Classification: FULFILLED-PORT (platform-facade Desktop actual).
 * Unit kind: Desktop concrete impl of the commonMain DeviceTierProbe SPI
 * (interface swept at cluster146, sibling 167). One of a 3-actual fan
 * alongside AndroidDeviceTierProbe + IosDeviceTierProbe.
 *
 * FULFILLED-PORT evidence: this is the Phase 5.w.6.5 (Task #187) relocation
 * verbatim-semantic-ported from the legacy
 * shared/desktopMain/.../core/util/heap/DeviceTier.desktop.kt into the new
 * interface impl. SOLID_AUDIT.md "Phase 5.w.6.5 — end-of-slice verdict"
 * (SOLID_AUDIT.md:2706 onward) records all four files pass and that the
 * Desktop load-bearing fixes are preserved: reflective sun-bean probe plus
 * runCatching fall-through (SOLID_AUDIT.md:2717) and unbounded-heap
 * classification as MID (SOLID_AUDIT.md:2718, also noted at :2700-2704 where
 * the legacy internal-const sentinel is replaced by an explicit
 * return DeviceTier.MID). The commonMain interface decl at
 * platform/src/commonMain/.../device/DeviceTierProbe.kt:79 is the contract.
 *
 * LIVE-as-wired status: NOT-YET-BOUND. Repo-wide grep for a Koin binding of
 * DeviceTierProbe or DesktopDeviceTierProbe across all di modules
 * (the composeApp di-package ReworkModule.kt files, ReworkModules.kt, IosKoin.kt) returns
 * ZERO bindings and ZERO rework consumers. Same dormant-relocation state as
 * the commonMain decl's cluster146 postscript: the SPI shipped but no rework
 * heap-budget consumer exists yet; the legacy detectDeviceTier() still serves
 * the live :shared consumers. This actual is correct, compiled, and waiting —
 * not orphaned.
 *
 * Delta-axes (Desktop actual specifics):
 *  1. Platform API: reflective ManagementFactory.getOperatingSystemMXBean()
 *     cast to com.sun.management.OperatingSystemMXBean via Class.forName, then
 *     getTotalPhysicalMemorySize() invoked reflectively — keeps the build
 *     decoupled from com.sun being visible at compile time.
 *  2. Threading/dispatcher: synchronous, non-suspending detect(); MXBean and
 *     Runtime queries are local JVM calls, no dispatcher hop. Honors the
 *     interface non-suspending contract.
 *  3. Error handling: runCatching wraps the whole reflective probe; on any
 *     failure logs at debug via Kermit and getOrNull() falls through to the
 *     Runtime.maxMemory() times 4 heuristic, with Long.MAX_VALUE (unbounded
 *     heap) short-circuiting to DeviceTier.MID to avoid overflow.
 *  4. DI binding mechanism: a zero-arg single { DesktopDeviceTierProbe() }
 *     would suffice (no Context, unlike Android); none exists yet (see
 *     NOT-YET-BOUND above).
 *  5. Contract parity vs the other two actuals: terminates in the same shared
 *     classifyByTotalRam(totalRamBytes) helper in :core/util/heap, so the
 *     LOW/MID/HIGH boundaries match Android and iOS exactly; only the
 *     RAM-source query differs (reflective sun-bean plus heuristic fallback
 *     here vs Context-backed on Android vs NSProcessInfo on iOS).
 *
 * Nested-comment hazard check: this file has 1 legitimate KDoc opener (the
 * class header above). This appended block adds exactly 1 opener and 1 closer,
 * with no interior delimiter sequences (no slash-star, no star-slash, no
 * slash-star-star anywhere in the prose). Balanced.
 */
