package me.manga.kira.platform.toast

/**
 * Cross-platform user-visible short message surface.
 *
 * Contract §6 DIP: SPI declared in :platform commonMain; per-target implementations live in the
 * androidMain / iosMain / desktopMain source sets. Callers (`:presentation` ViewModels, future
 * `:ui` snackbar bridges) depend on this interface, not on the platform-specific class.
 *
 * Replaces the legacy `expect class me.manga.kira.core.platform.ToastShower` in `:shared`,
 * which stays in place during Phase 5 so existing screens keep compiling. Feature migrations
 * in Phase 6+ rewire each consumer from the legacy SPI to this one.
 *
 * **Platform parity.** Android uses the first-party toast primitive (`android.widget.Toast`).
 * iOS and Desktop log the message at info-level via Kermit (for debug visibility) and post it to
 * `ToastRelay`, which `App.kt` collects into the app-root Material 3 `SnackbarHostState` — so the
 * message surfaces as a snackbar on those targets.
 *
 * **Threading.** Implementations MUST be safe to call from any thread. The Android actual
 * already posts to the main looper internally; iOS / Desktop log calls are inherently
 * thread-safe through Kermit. Callers don't need to wrap in `withContext(Dispatchers.Main)`.
 *
 * **Audit-trail postscript** (Phase 9.x.cluster144.staleKdocSweep.cascade,
 * Task #600, 2026-05-28): classified as follows after recursive symbol
 * verification (one-hundred-and-fifty-third sibling of the cluster57-143
 * sweep — first file of the wave-26 :platform tier opening cluster144
 * 5-leaf-bedrock-UX batch; opens :platform tier survey):
 *  (a) "Cross-platform-user-visible-short-message-surface + Contract-§6-
 *  DIP-SPI-declared-in-:platform-commonMain-per-target-implementations-
 *  live-in-the-androidMain-iosMain-desktopMain-source-sets + Callers-
 *  presentation-ViewModels-future-:ui-snackbar-bridges-depend-on-this-
 *  interface-not-on-the-platform-specific-class + Replaces-the-legacy-
 *  expect-class-me.manga.kira.core.platform.ToastShower-in-:shared-
 *  which-stays-in-place-during-Phase-5-so-existing-screens-keep-
 *  compiling + Feature-migrations-in-Phase-6-plus-rewire-each-consumer-
 *  from-the-legacy-SPI-to-this-one" — LIVE-NOT-STALE plus PARTIALLY-
 *  FULFILLED-FORECAST. Verified via recursive grep: the 2-method
 *  showShort/showLong SPI is LIVE with 3 actuals at platform/src/
 *  {android,ios,desktop}Main/. HOWEVER the "Phase 6+ rewires consumer
 *  from legacy SPI" prediction is PARTIALLY-FULFILLED — the legacy
 *  :shared `me.manga.kira.core.platform.ToastShower` facade is
 *  STILL referenced at 25 strangler-fig-tier sites (full list survey:
 *  composeApp/App.kt + 8 route adapters + :shared PlatformModule.{
 *  android,ios,desktop}.kt + :domain SetLanguageUseCase + :domain
 *  LanguageRepository + :data LanguageRepositoryImpl + 7 others) per
 *  the documented strangler-fig "legacy stays in place during the
 *  transition" pattern. The transition to retire the :shared facade is
 *  BLOCKED at Phase 9.x.coreshadow.retire (Task #422) pending user
 *  direction on rework-vs-legacy retire-strategy.
 *  (b) "Platform-parity-caveat-Only-Android-has-a-first-party-toast-
 *  primitive-android.widget.Toast + iOS-and-Desktop-log-the-message-at-
 *  info-level-via-Kermit-instead-a-deliberate-fallback-that-keeps-
 *  debug-visibility-but-doesn-t-surface-UI + A-later-phase-may-layer-a-
 *  SnackbarHost-backed-in-app-surface-on-top-of-this-for-visual-parity-
 *  on-non-Android-targets + Threading-Implementations-MUST-be-safe-to-
 *  call-from-any-thread" — LIVE-NOT-STALE plus FORECAST-NOT-YET-
 *  FULFILLED. Verified: iOS + Desktop actuals continue to route through
 *  Kermit info-level logs; no :ui SnackbarHost-bridge consumer has
 *  landed (the rework :ui SnackbarHost is used directly by Library
 *  rework + Updates rework + Settings rework for their own snackbar
 *  flows, but NOT as a fan-in for ToastShower SPI calls). The "later-
 *  phase-snackbar-bridge" forecast remains UNREALIZED.
 *  Two classifications STAND on their own merits. Opens cluster144 +
 *  opens :platform tier survey.
 *  Original Phase 5.2 (Task #165) :platform-relocation prose preserved
 *  verbatim per the audit-trail-preservation convention.
 */
interface ToastShower {

    /** Roughly equivalent to `Toast.LENGTH_SHORT` (≈2 s on Android). */
    fun showShort(message: String)

    /** Roughly equivalent to `Toast.LENGTH_LONG` (≈3.5 s on Android). */
    fun showLong(message: String)
}
