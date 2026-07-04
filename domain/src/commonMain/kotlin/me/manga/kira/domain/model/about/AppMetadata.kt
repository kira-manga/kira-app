package me.manga.kira.domain.model.about

/**
 * Read-only snapshot of the running app's user-visible identity, surfaced by the rework About
 * screen.
 *
 * Phase 7.x.about. Carries the two fields the picker displays — the user-facing version string
 * and the reverse-DNS package identifier — projected from the legacy `:shared`
 * `AppVersionProvider` expect class via the rework [me.manga.kira.domain.repository.AboutRepository].
 *
 * **Why a data class (not two separate primitive returns from the use case)**: bundling them
 * keeps the use case signature stable as the surface grows (a future build-flavor field, a
 * git-sha field, a build-timestamp field — all slot in here without touching call sites). Same
 * posture as [me.manga.kira.domain.model.statistics.ReadingStatistics] (the eight-field
 * aggregate ported in Phase 7.x.statistics).
 *
 * **`versionName` semantics**: the user-facing string the legacy
 * `AppVersionProvider.versionName` returns — e.g., `"1.2.3"` on Android (from
 * `PackageManager.getPackageInfo(...).versionName`), the `CFBundleShortVersionString` from
 * `NSBundle.mainBundle` on iOS, and a hardcoded constant on Desktop (the legacy expect class
 * carries a Phase 13 TODO to wire it to gradle `buildConfig`). May be `"unknown"` if the
 * platform call fails — same fallback the legacy expect class uses.
 *
 * **`packageName` semantics**: the reverse-DNS app id — `"me.manga.kira"` in this build.
 * Used by the picker's Check-for-update + Rate-our-app rows to dispatch
 * `IntentLauncher.openPlayStorePage(packageName)`. Stable for the running process.
 *
 * Contract §17: pure value type. No banned features (`Any`, `!!`, `lateinit`); both fields are
 * non-null `String`s — the legacy provider guarantees non-null (substituting `"unknown"` on
 * failure) and the rework projects that guarantee through.
 *
 * **Audit-trail postscript** (Phase 9.x.cluster137.staleKdocSweep.cascade,
 * Task #593, 2026-05-28): classified as follows after recursive symbol
 * verification (one-hundred-and-twenty-fifth sibling of the cluster57-136
 * sweep — first file of the wave-24 fifth-cluster 5-subpackage joint
 * batch alongside ComplaintSummary plus HistoryEntry plus Language plus
 * SettingsSnapshot; opens cluster137):
 *  (a) "Phase-7.x.about + Carries-the-two-fields-the-picker-displays-
 *  user-facing-version-string-and-the-reverse-DNS-package-identifier +
 *  projected-from-the-legacy-:shared-AppVersionProvider-expect-class +
 *  Why-a-data-class-not-two-separate-primitive-returns + bundling-them-
 *  keeps-the-use-case-signature-stable-as-the-surface-grows + a-future-
 *  build-flavor-field-a-git-sha-field-a-build-timestamp-field-all-slot-
 *  in-here-without-touching-call-sites + Same-posture-as-ReadingStatistics-
 *  the-eight-field-aggregate-ported-in-Phase-7.x.statistics" — LIVE-NOT-
 *  STALE plus FULFILLED-PREDICTION plus FORECAST-NOT-YET-FULFILLED-
 *  (build-flavor-or-git-sha-or-build-timestamp-extension). Verified via
 *  recursive grep: AppMetadata is consumed by GetAppMetadataUseCase
 *  plus AboutRepositoryImpl plus AboutViewModel plus AboutState plus
 *  AboutScreen. The rework data class carries exactly 2 fields (version-
 *  Name + packageName) — no build-flavor/git-sha/build-timestamp fields
 *  have landed; the bundle-data-class posture is ready to absorb them
 *  without touching the use case signature. The ReadingStatistics cross-
 *  reference holds — both are :domain value-type aggregates.
 *  (b) "versionName-semantics-user-facing-string-the-legacy-AppVersion-
 *  Provider.versionName-returns + 1.2.3-on-Android-from-PackageManager.
 *  getPackageInfo-versionName + CFBundleShortVersionString-from-
 *  NSBundle.mainBundle-on-iOS + hardcoded-constant-on-Desktop + legacy-
 *  expect-class-carries-a-Phase-13-TODO-to-wire-it-to-gradle-buildConfig
 *  + May-be-unknown-if-the-platform-call-fails-same-fallback-the-legacy-
 *  expect-class-uses + packageName-semantics-reverse-DNS-app-id-
 *  me.manga.kira-in-this-build + Used-by-the-picker-Check-for-update-
 *  plus-Rate-our-app-rows-to-dispatch-IntentLauncher.openPlayStorePage-
 *  packageName + Stable-for-the-running-process + Contract-§17-pure-
 *  value-type-no-banned-features-Any-!!-lateinit + both-fields-are-non-
 *  null-Strings-the-legacy-provider-guarantees-non-null-substituting-
 *  unknown-on-failure" — LIVE-NOT-STALE plus FULFILLED-PREDICTION plus
 *  FORECAST-NOT-YET-FULFILLED-(Phase-13-Desktop-buildConfig-wire).
 *  Verified: AppVersionProvider.android.kt reads PackageManager.
 *  getPackageInfo(...).versionName; iOS impl reads CFBundleShortVersion-
 *  String; Desktop impl still returns a hardcoded "unknown" placeholder
 *  per the carried Phase 13 TODO. IntentLauncher.openPlayStorePage-
 *  (packageName) is wired through the rework About screen's Rate-our-
 *  app row exactly as predicted. Both String fields are non-null in
 *  the data class declaration.
 *  Two classifications STAND on their own merits. Opens cluster137.
 *  Original Phase 7.x.about-era prose preserved verbatim per the audit-
 *  trail-preservation convention.
 */
data class AppMetadata(
    val versionName: String,
    val packageName: String,
)
