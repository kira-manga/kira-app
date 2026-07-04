package me.manga.kira.platform.device

import android.app.ActivityManager
import android.content.Context
import co.touchlab.kermit.Logger
import me.manga.kira.core.util.heap.DeviceTier
import me.manga.kira.core.util.heap.classifyByTotalRam

/**
 * Android actual for [DeviceTierProbe].
 *
 * Reads total device RAM via [ActivityManager.MemoryInfo.totalMem] (available since API 16; the
 * project's `minSdk` is 26 so the call is unconditional), then buckets the result with
 * `classifyByTotalRam(totalRamBytes)` from `:core`.
 *
 * Constructor convention: takes the application [Context] directly so Koin can bind it via a
 * standard `single { AndroidDeviceTierProbe(androidContext()) }`. Replaces the legacy
 * `setAndroidDeviceTierContext(ctx)` opt-in registration (which was a workaround for Koin's
 * commonMain bindings not seeing `Context` — the rework explicitly types it on the Android side).
 *
 * If [ActivityManager] is unavailable (effectively unreachable on a real device, but guards
 * against test-harness contexts), returns [DeviceTier.MID] as a safe middle-of-the-road default
 * and logs a warning so the misconfigured environment is surfaced.
 */
class AndroidDeviceTierProbe(private val context: Context) : DeviceTierProbe {

    private val log = Logger.withTag(TAG)

    override fun detect(): DeviceTier {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        if (am == null) {
            log.w { "ACTIVITY_SERVICE unavailable; returning DeviceTier.MID" }
            return DeviceTier.MID
        }
        val memInfo = ActivityManager.MemoryInfo()
        am.getMemoryInfo(memInfo)
        return classifyByTotalRam(memInfo.totalMem)
    }

    private companion object {
        const val TAG = "DeviceTierProbe.android"
    }
}

/*
 * §253 audit-trail postscript — cluster268 §253 sweep (2026-05-29)
 * Classification: FULFILLED-PORT (platform-facade Android actual).
 * Unit kind: Android concrete impl of the commonMain DeviceTierProbe SPI
 * (interface swept at cluster146, sibling 167). One of a 3-actual fan
 * alongside DesktopDeviceTierProbe + IosDeviceTierProbe.
 *
 * FULFILLED-PORT evidence: this is the Phase 5.w.6.5 (Task #187) relocation
 * of the legacy expect-fun detectDeviceTier() into a mockable, injectable
 * interface impl. The relocation is recorded as shipped + green in
 * SOLID_AUDIT.md "Phase 5.w.6.5 — end-of-slice verdict" (SOLID_AUDIT.md:2706
 * onward): all four files pass, :platform Android+Desktop+iOS build green,
 * :composeApp:compileDebugKotlinAndroid green (strangler-fig, no caller
 * rewired). The commonMain interface decl at
 * platform/src/commonMain/.../device/DeviceTierProbe.kt:79 is the expect-side
 * contract this class realizes.
 *
 * LIVE-as-wired status: NOT-YET-BOUND. A repo-wide grep for a Koin binding
 * (single/factory) of DeviceTierProbe or AndroidDeviceTierProbe across every
 * di module — the composeApp di-package ReworkModule.kt files, ReworkModules.kt, IosKoin.kt,
 * shared/.../di/PlatformModule.android.kt — returns ZERO bindings and ZERO
 * rework consumers. This matches the commonMain decl's own cluster146
 * postscript: the SPI landed (3 actuals + interface) but the predicted call-site
 * migration never executed. The two live heap-budget consumers
 * (OptimizedCbzManager + ProMangaImageCombiner) remain :shared-resident and
 * still call the legacy detectDeviceTier() top-level function. So this actual
 * is a ready-but-dormant relocation: correct, compiled, and waiting for the
 * first rework heap-budget consumer. Not orphaned (it is the canonical Android
 * impl of a live interface); simply not yet injected.
 *
 * Delta-axes (Android actual specifics):
 *  1. Platform API: ActivityManager.MemoryInfo.totalMem via
 *     context.getSystemService(ACTIVITY_SERVICE) — needs an application Context,
 *     injected per the single-arg ctor convention (Koin androidContext()).
 *  2. Threading/dispatcher: synchronous, non-suspending detect(); the OS RAM
 *     query is a cheap local call, no dispatcher hop. Matches the interface
 *     KDoc contract ("Implementations are non-suspending").
 *  3. Error handling: null-guards the ACTIVITY_SERVICE cast; on a missing
 *     service logs a warning via Kermit and returns DeviceTier.MID (safe
 *     middle default) rather than throwing.
 *  4. DI binding mechanism: intended single { AndroidDeviceTierProbe(androidContext()) }
 *     replacing the legacy setAndroidDeviceTierContext(ctx) opt-in registration;
 *     no such single exists in any module yet (see NOT-YET-BOUND above).
 *  5. Contract parity vs the other two actuals: all three terminate in the
 *     shared classifyByTotalRam(totalRamBytes) helper in :core/util/heap, so
 *     the LOW/MID/HIGH boundaries are identical across platforms; only the
 *     RAM-source query differs (Android Context-backed vs Desktop reflective
 *     vs iOS NSProcessInfo).
 *
 * Nested-comment hazard check: this file has 1 legitimate KDoc opener (the
 * class header above this class). This appended block adds exactly 1 opener and
 * 1 closer, with no interior delimiter sequences (no slash-star, no star-slash,
 * no slash-star-star anywhere in the prose). Balanced.
 */
