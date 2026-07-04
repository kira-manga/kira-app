package me.manga.kira.domain.auth

/**
 * Returns a stable, app-unique user ID. Implementations are platform-specific
 * (Android uses Settings.Secure.ANDROID_ID, iOS uses identifierForVendor, etc.)
 * and live in their respective platform source sets — Phase 8 will provide
 * `androidMain`/`iosMain`/`desktopMain` actuals via Koin platform modules.
 */
interface UserIdProvider {
    fun getUserId(): String
}

/*
 * Audit-trail postscript (Phase 9.x.cluster212.staleKdocSweep.cascade, Task #668, 2026-05-29)
 * --------------------------------------------------------------------------------------------
 * Cluster212 leaf 3/4 — :shared/domain/auth/ tier SINGLE-LEAF, sibling 395. Cumulative
 * §253-postscript count = 120 leaves with this commit.
 *
 * File-shape note: 11-line file — single 1-member interface (fun getUserId(): String) + 6-line
 * KDoc forecast prose (lines 3-8) describing the Phase-8 platform-actual seam plan
 * (Android Settings.Secure.ANDROID_ID + iOS identifierForVendor + Desktop fallback).
 *
 * Body-level deltas (cluster57+ taxonomy):
 *
 *   • LIVE-NOT-STALE + FULFILLED-FORECAST — interface SPI with 3 platform actuals delivered:
 *       1. shared/src/androidMain/.../domain/auth/AndroidUserIdProvider.kt
 *       2. shared/src/iosMain/.../domain/auth/IosUserIdProvider.kt
 *       3. shared/src/desktopMain/.../domain/auth/DesktopUserIdProvider.kt
 *      All 3 actuals are LIVE — bound via shared/src/{androidMain,iosMain,desktopMain}/di/
 *      PlatformModule.{android,ios,desktop}.kt Koin platform modules. The Phase-8 forecast
 *      in the KDoc (lines 3-8) IS FULFILLED — actuals were delivered per the original plan.
 *
 *   • KDOC-FORECAST-FULFILLED-NOT-STALE — 6-line KDoc prose (lines 3-8) carries an
 *     intentionally-historical forecast: "Phase 8 will provide androidMain/iosMain/desktopMain
 *     actuals via Koin platform modules." PRESERVE — the forecast IS fulfilled (3 actuals
 *     LIVE), but the prose remains valuable as design-intent documentation: a future reader
 *     opening this file learns the SPI contract + WHY the impl is platform-split (Android
 *     ANDROID_ID, iOS identifierForVendor, Desktop fallback) without having to grep all 3
 *     actuals. Cluster188 wave-58 used this same FULFILLED-FORECAST classification for
 *     EmptyMangaRepository (sibling 300) — same posture.
 *
 *   • ARCHITECTURE-MD-DOCUMENTED — 1 ARCHITECTURE.md reference (verified via grep): the
 *     SPI is enumerated as a Phase 5/6 platform-facade. The contract surface is stable.
 *
 *   • CROSS-PACKAGE-DEPENDENCY-LIVE — 0 imports. Pure-Kotlin interface with no kotlinx /
 *     androidx / coil3 / etc. dependencies — pristine domain-tier shape.
 */
