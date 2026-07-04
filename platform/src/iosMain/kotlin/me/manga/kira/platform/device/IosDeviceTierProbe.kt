package me.manga.kira.platform.device

import me.manga.kira.core.util.heap.DeviceTier
import me.manga.kira.core.util.heap.classifyByTotalRam
import platform.Foundation.NSProcessInfo

/**
 * iOS actual for [DeviceTierProbe].
 *
 * `NSProcessInfo.processInfo.physicalMemory` returns total RAM in bytes as a `ULong`. We clamp to
 * `Long.MAX_VALUE` before narrowing to signed — every shipping iOS device sits well below 2^63
 * bytes, so the clamp is purely defensive for the type conversion.
 *
 * Verbatim semantic port from legacy `:shared/iosMain/.../core/util/heap/DeviceTier.ios.kt`.
 */
class IosDeviceTierProbe : DeviceTierProbe {

    override fun detect(): DeviceTier {
        val physicalBytes: ULong = NSProcessInfo.processInfo.physicalMemory
        val clamped: Long = if (physicalBytes > Long.MAX_VALUE.toULong()) {
            Long.MAX_VALUE
        } else {
            physicalBytes.toLong()
        }
        return classifyByTotalRam(clamped)
    }
}

/*
 * §253 audit-trail postscript — cluster268 §253 sweep (2026-05-29)
 * Classification: FULFILLED-PORT (platform-facade iOS actual).
 * Unit kind: iOS concrete impl of the commonMain DeviceTierProbe SPI
 * (interface swept at cluster146, sibling 167). One of a 3-actual fan
 * alongside AndroidDeviceTierProbe + DesktopDeviceTierProbe.
 *
 * FULFILLED-PORT evidence: this is the Phase 5.w.6.5 (Task #187) relocation
 * verbatim-semantic-ported from the legacy
 * shared/iosMain/.../core/util/heap/DeviceTier.ios.kt into the new interface
 * impl. SOLID_AUDIT.md "Phase 5.w.6.5 — end-of-slice verdict"
 * (SOLID_AUDIT.md:2706 onward) records all four files pass and the iOS
 * load-bearing fix preserved: the ULong to Long defensive clamp
 * (SOLID_AUDIT.md:2716). The iosArm64 plus iosSimulatorArm64 :platform targets
 * build green in that verdict; the iOS framework link itself was not executed
 * (Windows host), per project memory iOS compile happens on the user's Mac.
 * The commonMain interface decl at
 * platform/src/commonMain/.../device/DeviceTierProbe.kt:79 is the contract;
 * IosDeviceTierProbe declares the actual at IosDeviceTierProbe.kt:16.
 *
 * LIVE-as-wired status: NOT-YET-BOUND. Repo-wide grep for a Koin binding of
 * DeviceTierProbe or IosDeviceTierProbe across all di modules
 * (the composeApp di-package ReworkModule.kt files, ReworkModules.kt, IosKoin.kt) returns
 * ZERO bindings and ZERO rework consumers. Same dormant-relocation state as
 * the commonMain decl's cluster146 postscript: the SPI shipped (3 actuals plus
 * interface) but no rework heap-budget consumer exists yet; the legacy
 * detectDeviceTier() still serves the live :shared consumers. Correct,
 * compiled, waiting — not orphaned.
 *
 * Delta-axes (iOS actual specifics):
 *  1. Platform API: NSProcessInfo.processInfo.physicalMemory (Foundation),
 *     which returns total RAM in bytes as a Kotlin/Native ULong.
 *  2. Threading/dispatcher: synchronous, non-suspending detect(); a single
 *     Foundation property read, no dispatcher hop. Honors the interface
 *     non-suspending contract.
 *  3. Error handling: no try-catch — the only hazard is the unsigned-to-signed
 *     conversion, handled by a defensive clamp to Long.MAX_VALUE before
 *     toLong() (every shipping iOS device sits well below 2^63 bytes, so the
 *     clamp is purely a type-safety guard, not an expected runtime path).
 *  4. DI binding mechanism: a zero-arg single { IosDeviceTierProbe() } would
 *     suffice (no Context, like Desktop); none exists yet (see NOT-YET-BOUND
 *     above). On iOS, Koin is assembled via IosKoin.kt, which currently carries
 *     no DeviceTierProbe binding.
 *  5. Contract parity vs the other two actuals: terminates in the same shared
 *     classifyByTotalRam(totalRamBytes) helper in :core/util/heap, so the
 *     LOW/MID/HIGH boundaries match Android and Desktop exactly; only the
 *     RAM-source query differs (NSProcessInfo here vs Context-backed on Android
 *     vs reflective sun-bean on Desktop).
 *
 * Nested-comment hazard check: this file has 1 legitimate KDoc opener (the
 * class header above). This appended block adds exactly 1 opener and 1 closer,
 * with no interior delimiter sequences (no slash-star, no star-slash, no
 * slash-star-star anywhere in the prose). Balanced.
 */
