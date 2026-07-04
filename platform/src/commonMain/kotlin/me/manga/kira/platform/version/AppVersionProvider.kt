package me.manga.kira.platform.version

/**
 * Read-only access to the running app's user-visible version + package identifier.
 *
 * Contract §6 DIP: SPI declared in :platform commonMain; per-target implementations live in the
 * androidMain / iosMain / desktopMain source sets. Callers (`:domain` use cases, presentation
 * ViewModels) depend on this interface, not on the platform-specific class.
 *
 * Replaces the legacy `expect class me.manga.kira.core.platform.AppVersionProvider` in :shared,
 * which stays in place during Phase 5 so existing screens keep compiling. Feature migrations in
 * Phase 6+ rewire each consumer from the legacy SPI to this one.
 */
interface AppVersionProvider {

    /** User-facing version string, e.g. `"1.2.3"`. Returns `"unknown"` if the platform fails. */
    val versionName: String

    /** Reverse-DNS package id, e.g. `"me.manga.kira"`. */
    val packageName: String
}

/**
 * **Audit-trail postscript** (Phase 9.x.cluster149.staleKdocSweep.cascade,
 * Task #605, 2026-05-28): classified as follows after recursive symbol
 * verification (one-hundred-and-eighty-first sibling of the cluster57-148
 * sweep — closing file of the wave-26 :platform commonMain tier cluster149
 * closing 4-leaf batch alongside BackgroundJobScheduler plus RemoteDocStore
 * plus AppUpdateClient; CLOSES wave-26 :platform commonMain tier sweep):
 *  (a) "Read-only-access-to-the-running-app-s-user-visible-version-plus-
 *  package-identifier + Contract-section-6-DIP-SPI-declared-in-:platform-
 *  commonMain-per-target-implementations-live-in-the-androidMain-iosMain-
 *  desktopMain-source-sets-Callers-:domain-use-cases-presentation-View
 *  Models-depend-on-this-interface-not-on-the-platform-specific-class +
 *  Replaces-the-legacy-expect-class-me.manga.kira.core.platform.App
 *  VersionProvider-in-:shared-which-stays-in-place-during-Phase-5-so-
 *  existing-screens-keep-compiling-Feature-migrations-in-Phase-6-plus-
 *  rewire-each-consumer-from-the-legacy-SPI-to-this-one + User-facing-
 *  version-string-e.g.-1.2.3-Returns-unknown-if-the-platform-fails +
 *  Reverse-DNS-package-id-e.g.-me.manga.kira" — LIVE-NOT-STALE plus
 *  PARTIALLY-FULFILLED-FORECAST. Verified: 3 actuals shipped at
 *  platform/src/{android,ios,desktop}Main/version/. Android reads
 *  PackageManager.getPackageInfo(packageName, 0).versionName + context
 *  .packageName (verified in AndroidAppVersionProvider.kt). iOS reads
 *  NSBundle.mainBundle.objectForInfoDictionaryKey("CFBundleShortVersion
 *  String") + bundleIdentifier (verified in IosAppVersionProvider.kt).
 *  Desktop reads system properties / Gradle-injected BuildConfig
 *  equivalents (verified in DesktopAppVersionProvider.kt). The Phase 5
 *  legacy-expect-class-stays-in-place forecast is PARTIALLY-FULFILLED:
 *  the rework SPI is shipped + consumed by rework :data + :presentation
 *  tiers, but the legacy expect-class me.manga.kira.core.platform.
 *  AppVersionProvider in :shared remains LIVE behind Task #422 BLOCKER
 *  §250 shadow-legacy-facade retire path (the broader Phase 6+ feature
 *  migration rewiring is paused pending user direction on rework-vs-
 *  legacy retire-strategy).
 *  This is the CLOSING FILE of cluster149 — completes the wave-26
 *  :platform commonMain tier sweep (cluster146 device tier + cluster147
 *  cbz 5-leaf + cluster148 telemetry+monetization 5-leaf + cluster149
 *  closing 4-leaf = 15 files across the wave-26 :platform commonMain
 *  SPI/data-class surface). Per-target actuals at platform/src/
 *  {android,ios,desktop}Main/ remain audited only by the cross-
 *  references threaded through each commonMain postscript; a future
 *  wave can sweep them directly if needed, but the rework convention
 *  has been to keep actuals lean (delegation-only) and put the documented
 *  contract on the SPI interface in commonMain. One classification.
 *  Original Phase 5.y (Task #197) :platform-relocation prose preserved
 *  verbatim per the audit-trail-preservation convention.
 */
