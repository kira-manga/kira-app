package me.manga.kira.domain.auth

import android.annotation.SuppressLint
import android.content.Context
import android.provider.Settings

/**
 * Android `UserIdProvider` — returns `Settings.Secure.ANDROID_ID`. Mirrors the upstream
 * `DeviceIdProvider` in `me.manga.yami.domain.auth`; the Hilt `@Inject`/`@Singleton` annotations
 * are dropped in favour of Koin module wiring (Phase 8 sub-task 8.12).
 *
 * Returns `"Unable to retrieve Android ID"` when the platform unexpectedly hands back `null`,
 * preserving the source string so any analytics dashboards keyed off it continue to match.
 */
class AndroidUserIdProvider(private val context: Context) : UserIdProvider {
    @SuppressLint("HardwareIds")
    override fun getUserId(): String =
        Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ANDROID_ID,
        ) ?: "Unable to retrieve Android ID"
}

/*
 * Audit-trail postscript (Phase 9.x.cluster213a.staleKdocSweep.cascade, Task #669, 2026-05-29)
 * --------------------------------------------------------------------------------------------
 * Cluster213a leaf 1/3 — :shared/androidMain/domain/auth/ Android actual tier, sibling 397.
 * Cumulative §253-postscript count = 122 leaves with this commit.
 *
 * File-shape note: 22-line file — `AndroidUserIdProvider` 1-method class (overrides
 * `getUserId(): String` via Settings.Secure.ANDROID_ID with "Unable to retrieve" fallback) +
 * 8-line class-level KDoc prose (lines 7-14) carrying Hilt→Koin migration lineage + fallback-
 * semantics design rationale.
 *
 * Body-level deltas (cluster57+ taxonomy):
 *
 *   • LIVE-NOT-STALE + FULFILLED-PORT — Android actual fulfilling the cluster212 sibling 395
 *     commonMain `UserIdProvider` SPI interface. Bound via shared/src/androidMain/di/
 *     PlatformModule.android.kt Koin platform module. Twin-fulfilled by IosUserIdProvider
 *     (sibling 398, this cluster) + DesktopUserIdProvider (sibling 399, this cluster).
 *
 *   • KDOC-MIGRATION-LINEAGE-LOAD-BEARING — 8-line KDoc prose documents the Phase 8 sub-task
 *     8.12 Hilt→Koin annotation drop ("the Hilt `@Inject`/`@Singleton` annotations are dropped
 *     in favour of Koin module wiring") + the analytics-fallback string preservation contract
 *     (returns the source-app's literal "Unable to retrieve Android ID" so analytics dashboards
 *     keyed off it continue to match). PRESERVE — design-intent doc; load-bearing for any
 *     future ANDROID_ID-fallback-string adjustment that would silently break analytics matching.
 *
 *   • CROSS-PACKAGE-DEPENDENCY-LIVE — 3 imports: android.annotation.SuppressLint +
 *     android.content.Context + android.provider.Settings. LIVE — Android-platform-only SPI.
 *
 *   • CLUSTER213A OPENER REGISTER — 3-leaf platform-actual fan-out for cluster212 sibling 395
 *     commonMain `UserIdProvider` SPI interface. Tier-totals:
 *       • leaf 1/3 sibling 397 OPENER — domain/auth/AndroidUserIdProvider.kt (Hilt→Koin port
 *         lineage + ANDROID_ID-fallback-string analytics-match preservation)
 *       • leaf 2/3 sibling 398 — domain/auth/IosUserIdProvider.kt (identifierForVendor +
 *         nil-IDFV early-launch defensive fallback)
 *       • leaf 3/3 sibling 399 CLOSER — domain/auth/DesktopUserIdProvider.kt (synthesized
 *         per-install UUID persisted under ~/.kira-manga/device-id with transient-UUID
 *         fallback for home-dir resolution failures)
 */
