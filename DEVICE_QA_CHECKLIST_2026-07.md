# Device-QA Checklist — 2026-07 (from REVIEW_BACKLOG_2026-07-02.md §8)

Run before any store submission. Code-side prerequisites are DONE (backlog Phase-2 commits
`30a39e2b`..`a9cb74b5`); everything below is device/console work only.

## Q1 — iOS background downloads: resolve-ahead scenario (primary)
Device: real iPhone, TestFlight or Xcode-run Release config.
1. Queue 5+ chapters of one manga; let ch1 start; BACKGROUND the app while ch1 transfers.
2. Expect ch2–4 to complete in background — verify via `prefetch.manifest.written` log lines
   (BgDownloadLog is ON for TestFlight/Debug distributions).
3. Force a Cloudflare/403 on the source mid-run (VPN/rate-limit) — expect prefetch to PAUSE
   (10-min pause on ANY prefetch failure) while real resolves continue unaffected.
4. Kill the app mid-batch; relaunch — reconciler must reset RUNNING/COMPRESSING orphans to
   QUEUED and resume (no frozen rows).
5. Confirm transfers stay strictly one-chapter and scrapes serialize (500 ms spacing).

## Q2 — R8 production-key smoke (Android)
1. `./gradlew :app:assembleRelease` with the REAL keystore env vars (online resolve).
2. Install on device: launch → Home fetch → details → read a chapter → download a chapter →
   push tap-through → settings toggles → theme/language change.
3. Watch Logcat for R8-only crashes (reflection/serialization/workers).

## Q3 — Feel pass (flagged in-code by their authors)
- Fast-scroller thumb drag: Library grid + Details chapter list (drag, fling, edge-swipe near
  the thumb — the gesture-exclusion seam), light + dark, RTL.
- Native iOS reader: paged zoom (pinch + double-tap), chrome auto-hide (3 s), scrubber seek
  across appended chapters, swipe-past-last-page advance, share (verify it captures the
  CENTERED page — new fix), exact-boundary slow drag then reopen (resume must be exact — new fix).
- Arabic locale: the reader full-screen error + Retry now localize (new fix) — verify strings.

## Q4 — Push end-to-end (after owner console steps: APNs .p8 uploaded, real plists)
- Cold-start tap on a manga push → lands on Details; warm tap → single instance (no stack).
- During onboarding: push tap must be DEFERRED (no navigation) until onboarding completes.
- Craft a push whose url does NOT belong to the claimed source api → must be REJECTED
  (host-trust guard) and open the app normally.

## Q5 — Logging distribution check
- TestFlight build: `BgDownloadLog.VERBOSE` lines present.
- App Store (production) build: VERBOSE absent (runtime-enforced by distribution) — verify once.

## Q6 — Visual check before coding backlog L4 — RESOLVED IN CODE 2026-07-03
- ~~Updates tab, light theme: queue a download and check the QUEUED spinner (onPrimary tint) is
  visible against the surface card.~~ Answered without a device: the :ui desktopTest pixel
  capture proved 0 visible pixels under the old onPrimary tint in the light theme; L4 landed
  (onSurfaceVariant) and `DownloadAffordanceVisibilityTest` pins visibility in light+dark.
  Optional on-device eyeball only.

## Q7 — iOS live language switch (PI2, landed 2026-07-03)
- Settings → Language: pick Arabic. EXPECT: strings re-resolve immediately (no relaunch) AND the
  layout mirrors to RTL in the same frame; digits (statistics counts, cache size) switch to
  Arabic-Indic shaping with the strings. Switch back to English: everything returns live.
- Known residual (by design): the native reader's Swift-side strings (ReaderStrings) and
  notification strings resolve via NSBundle → they catch up on the NEXT launch only.
- The OS behavior this depends on is pinned by AppleLanguagesLiveSwitchContractTest
  (:composeApp iosSimulatorArm64Test); if that test ever fails, flip
  LocalAppLocale.ios isLiveLocaleSwitchSupported back to false.

Record results per item (pass/fail + notes) and feed failures back into the backlog.
