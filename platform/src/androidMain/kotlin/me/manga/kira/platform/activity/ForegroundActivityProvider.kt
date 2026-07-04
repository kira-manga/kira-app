package me.manga.kira.platform.activity

import android.app.Activity

/**
 * Cross-cutting type alias for the "give me the current foreground [Activity], or null if there
 * isn't one" lambda used by Android `:platform` actuals that have to launch UI surfaces (Play
 * Core review / app-update dialogs, Google UMP consent form, AdMob full-screen ads, ...).
 *
 * Hoisted to a single declaration in Phase 5.z.cleanup so the four `(context, activityProvider)`
 * actuals from the Phase 5.z third-party-service track (`AndroidInAppReviewClient`,
 * `AndroidAppUpdateClient`, `AndroidConsentFlowClient`, `AndroidAdProvider`) share one named
 * shape instead of duplicating `() -> Activity?` four times. The host (typically
 * `:composeApp/androidMain`) constructs each facade with the same lambda — usually something
 * like `{ MainActivity.current?.get() }` — and the named alias both documents the contract and
 * keeps the SOLID Guardian's "no anonymous function-typed constructor params" preference
 * satisfied.
 *
 * **Why a typealias, not an interface**: a single-method functional shape with no behavior
 * worth subtype-testing — a typealias keeps the call-site ergonomics of a plain lambda
 * (`provider()`) while still giving the parameter a readable name. Wrapping it in a fun
 * interface would force callers to write `ForegroundActivityProvider { … }` at construction,
 * which buys nothing here.
 *
 * **Default**: callers that don't have a foreground Activity (unit tests, background jobs,
 * tooling launchers) can pass `{ null }` — every consumer in the Phase 5.z group already
 * handles a `null` Activity by returning the SPI's safe-default (`false` / `UNKNOWN` /
 * `AdResult.Failed`).
 */
typealias ForegroundActivityProvider = () -> Activity?

/*
 * Audit-trail postscript (Phase 9.x.cluster247.staleKdocSweep.cascade, Task #703, 2026-05-29)
 * --------------------------------------------------------------------------------------------
 * Cluster247 leaf 4 of 5 — :platform androidMain activity ForegroundActivityProvider,
 * sibling 510 of 5-LEAF-MIXED-OUTLIER-PLATFORM-ACTUAL-SUB-TIER-OPENER sweep.
 * Cumulative section-253-postscript count = 234 leaves with this commit.
 *
 * File-shape note: 30-line file (pre-postscript) — file-level KDoc (25
 * lines) preserved verbatim. 1 top-level `typealias` declaration
 * (ForegroundActivityProvider = () -> Activity?). 1 import (android.app.
 * Activity). NO companion. NO fun. ANDROID-ONLY-NO-EXPECT-NO-ACTUAL-
 * SINGLE-PLATFORM-OUTLIER.
 *
 * Body-level deltas (cluster57 plus taxonomy):
 *
 *   - ANDROID-SINGLE-PLATFORM-TYPEALIAS-LIVE — File declares
 *     `typealias ForegroundActivityProvider = () -> Activity?`. The
 *     typealias IS Android-only (Activity IS android.app, no expect/
 *     actual pair, no iOS/Desktop equivalent). 1-DIVERGES from typical
 *     :platform expect/actual triad shape (most :platform facades have
 *     commonMain interface plus 3 actuals). PRESERVE — load-bearing
 *     because the 4 Phase-5.z Android facades (AndroidInAppReviewClient,
 *     AndroidAppUpdateClient, AndroidConsentFlowClient, AndroidAdProvider)
 *     all need to launch UI surfaces that REQUIRE an Activity reference
 *     (Play Core dialogs, Google UMP consent form, AdMob full-screen
 *     ads). iOS/Desktop facades for the same SPIs don't need Activity
 *     (different platform UI plumbing).
 *
 *   - PHASE-5-Z-CLEANUP-CITATION-LIVE — KDoc cites "Phase 5.z.cleanup"
 *     as the hoist event (typealias was introduced to share one named
 *     lambda shape across 4 Phase 5.z actuals instead of duplicating
 *     `() -> Activity?` four times). The Phase 5.z citation IS load-
 *     bearing for git-archaeology (Task #195 was the Phase 5.z.cleanup
 *     marker — see task #195 in tracker). PRESERVE.
 *
 *   - TYPEALIAS-VS-FUN-INTERFACE-RATIONALE-LIVE — KDoc explicitly
 *     justifies typealias over `fun interface ForegroundActivityProvider`
 *     citing the SAM-call-site ergonomic (`provider()` works for both
 *     forms, but typealias avoids the `ForegroundActivityProvider { … }`
 *     wrapper at construction). The rationale IS load-bearing as
 *     architectural-decision residue. PRESERVE — defends against future
 *     "switch to fun interface for testability" refactor (which would
 *     force construction-site wrapping for no testability win).
 *
 *   - SOLID-GUARDIAN-NO-ANONYMOUS-FN-PARAM-PREFERENCE-LIVE — KDoc cites
 *     the SOLID Guardian preference "no anonymous function-typed
 *     constructor params" as a secondary rationale for the named alias.
 *     The Guardian citation IS load-bearing as project-convention
 *     residue (callers can write the lambda inline at construction but
 *     the param signature reads as a named shape). PRESERVE.
 *
 *   - NULLABLE-RETURN-DEFAULT-NULL-CONVENTION-LIVE — Typealias returns
 *     `Activity?` (nullable). KDoc documents the `{ null }` default
 *     pattern for unit-test/background-job/tooling callers that don't
 *     have a foreground Activity. The nullable-with-null-default IS
 *     load-bearing because every Phase 5.z consumer already handles
 *     `null` Activity by returning the SPI's safe-default (false /
 *     UNKNOWN / AdResult.Failed). PRESERVE.
 *
 *   - MAINACTIVITY-CURRENT-GET-DEFAULT-CALL-SITE-LIVE — KDoc cites
 *     `{ MainActivity.current?.get() }` as the canonical
 *     :composeApp/androidMain construction-site call shape. The cited
 *     call-site IS load-bearing as documentation of the WeakReference-
 *     backed access pattern (MainActivity holds a WeakReference to the
 *     current foreground Activity to avoid leaking it across config
 *     changes / destruction). PRESERVE.
 *
 *   - SINGLE-PLATFORM-NO-EXPECT-OUTLIER-FLAG-LIVE — File has NO
 *     commonMain expect declaration. The Android-only shape IS an
 *     intentional outlier in the :platform module (most :platform
 *     facades have triad shape). 2-AGREE-WITH-cluster247-LEAF-5
 *     (HighQualitySkiaImageDecoder.kt IS nonAndroidMain-only, also a
 *     single-platform outlier). PRESERVE — load-bearing because the
 *     Android-only nature IS the contract (SPI consumers know
 *     ForegroundActivityProvider only exists on Android).
 *
 *   - NO-COMPANION-OBJECT-LIVE — 5-AGREE-AT-cluster247-projected.
 *     PRESERVE.
 *
 *   - WAVE-REGISTER-CONTINUES-cluster247-LIVE — ForegroundActivity
 *     Provider.kt IS leaf 4 of 5 of cluster247 SINGLE-PLATFORM-OUTLIER
 *     half of the batch (leaves 1+2+3 are the :core IoDispatcher
 *     3-actual fan; leaves 4+5 are unrelated single-platform outliers).
 *     PRESERVE.
 */

