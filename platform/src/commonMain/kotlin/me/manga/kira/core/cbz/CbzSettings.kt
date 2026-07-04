package me.manga.kira.core.cbz

data class CbzSettings(
    val maxParallelDecode: Int,
    val maxParallelCompress: Int,
    val regionDecodeThreshold: Int,
    val samplingThreshold: Long,
    val webpQuality: Int,
)

/*
 * §253 audit-trail postscript — cluster279 §253 sweep (2026-05-29)
 * Classification: LEGACY-BUT-LIVE (pre-rework :shared/commonMain data class; a parallel
 * FULFILLED-PORT twin exists under :platform/cbz — see cross-reference below).
 *
 * LIVE evidence — this legacy data class is still reached by the legacy Android impl:
 *   - shared/androidMain/.../core/cbz/OptimizedCbzManager.kt:53
 *       `private val settings = getCbzSettings(tier)` returns a CbzSettings, then the
 *       five fields are read across 11 sites (lines 55, 56, 359, 360, 362, 363, 385,
 *       391, 392, 438 per SOLID_AUDIT.md:38074-38078). All five accessors are live.
 *   - shared/commonMain/.../core/cbz/getCbzSettings.kt:12 — the tier-to-settings lookup
 *       function constructs this exact class in its DeviceTier.LOW/MID/HIGH when-arms
 *       (lines 14, 21, 28). That getCbzSettings was itself swept at cluster180.
 *
 * FULFILLED-PORT cross-reference: the rework relocation lives at
 *   platform/commonMain/.../platform/cbz/CbzSettings.kt:52 (verbatim 5-field port,
 *   field names and types identical), picked per device tier by the sibling
 *   platform getCbzSettings (cluster147). That :platform twin documents (its own
 *   postscript lines 36-38) that the LEGACY OptimizedCbzManager still consumes THIS
 *   legacy CbzSettings, NOT the :platform one — so this file is NOT orphaned; the
 *   strangler-fig has not yet cut over the Android CBZ subsystem.
 *
 * Delta-axes (legacy commonMain vs rework :platform twin):
 *   1. Platform API used: none — pure Kotlin data class, zero imports, zero expect.
 *      Identical surface on both sides (5 vals: maxParallelDecode/maxParallelCompress
 *      both Int, regionDecodeThreshold Int, samplingThreshold Long, webpQuality Int).
 *   2. Threading/dispatcher: not applicable — immutable value type with no coroutine
 *      surface; consumers (OptimizedCbzManager) own the dispatching.
 *   3. Error handling: none — no failure modes; tunable parameters only.
 *   4. DI binding mechanism: NOT Koin-bound on either side. Produced by the pure
 *      getCbzSettings(tier) factory lookup, not a single/factory definition. The
 *      DeviceTier input is what flows from DeviceTierProbe (Task #187 actuals).
 *   5. Behavioural contract parity: the legacy and :platform field sets are byte-for-byte
 *      identical (ARCHITECTURE.md:2613-2616 "5 fields, same names, same"); per-tier
 *      magic numbers preserved across the port.
 * Nested-comment hazard check: this file has zero pre-existing KDoc or block-comment
 * openers (the original body is a bare data class). This appended block adds exactly one
 * opener and one closer, with no interior slash-star, star-slash, or slash-star-star
 * delimiter sequences; the block is balanced.
 */
