package me.manga.kira.sources.runtime

import kotlin.concurrent.Volatile

/**
 * Debug/test-only switches for the generic sources engine. NOT for production — every flag defaults to
 * the safe production value.
 */
object SourceDebugFlags {

    /**
     * **RETAINED-BUT-UNWIRED (2026-06).** This flag only affects [FallbackSourceClient], which
     * [DefaultSourceRegistry] no longer wires — config-backed sources are now generic-ONLY in production,
     * so a generic failure always surfaces directly (the legacy scraper is never executed). The flag has
     * no production effect now; it is kept (with `FallbackSourceClientDebugFlagTest`) only for the
     * isolated wrapper class, in case an opt-in per-source legacy fallback is re-introduced later.
     *
     * (Originally: when `true`, [FallbackSourceClient] ran generic-only and logged every verb's outcome
     * with tag `GenericSourceTest` so a failure could be categorised — Cloudflare/headers, HTTP status,
     * JSON-parse, missing config, empty selectors, etc.)
     */
    @Volatile
    var DISABLE_LEGACY_FALLBACK_FOR_GENERIC_TESTING: Boolean = false
}
