package me.manga.kira.presentation.features.download.domain.clean

/**
 * Pure decision rules for whether the iOS background engine may run the heavy CBZ (WebP) encode
 * ("compression") right now, and — when it may not — whether the block is specifically the
 * user-overridable Low Power Mode deferral (so the UI + notification can show a clear
 * "paused (Low Power Mode)" state instead of an endless "Finalizing…").
 *
 * No I/O — fully unit-tested, the admission-side companion of [FinalizeRules]. Extracted from the
 * engine's former inline `canCompressNow()` so the thermal/Low-Power/opt-in matrix is testable in
 * commonTest and reused identically by the just-completed path and the catch-up sweep.
 *
 * Two contexts:
 *  - **Foreground** ([canCompress] with `appActive = true`): the app must be *settled* (foregrounded
 *    long enough that a heavy encode won't collide with launch/reopen warm-up — the confirmed freeze)
 *    AND the device must not be *deferring* ([isDeferred]).
 *  - **Background** (`appActive = false`): allowed only inside a real OS-granted BG-task window
 *    ([backgroundWindowActive]); there is no UI to jank, so device-stress deferral does not apply.
 *
 * Two independent deferral causes:
 *  - **Thermal** (serious/critical): ALWAYS defers foreground compression — running the encoder would
 *    only worsen the thermal state. Not user-overridable.
 *  - **Low Power Mode**: defers foreground compression BY DEFAULT (respect the user's battery-saving
 *    intent), but the user may opt in ([allowLowPowerCompression]) to compress anyway.
 */
object CompressionGateRules {

    /**
     * True when the heavy CBZ encode may run right now.
     *
     * @param appActive whether the app is foreground.
     * @param appSettled whether the app has been foreground long enough (only meaningful when [appActive]).
     * @param thermallyStressed `ProcessInfo.thermalState` serious/critical.
     * @param lowPowerMode `ProcessInfo.isLowPowerModeEnabled`.
     * @param allowLowPowerCompression user opt-in to compress during Low Power Mode.
     * @param backgroundWindowActive an OS-granted BG-task CPU window is running (only meaningful when `!appActive`).
     */
    fun canCompress(
        appActive: Boolean,
        appSettled: Boolean,
        thermallyStressed: Boolean,
        lowPowerMode: Boolean,
        allowLowPowerCompression: Boolean,
        backgroundWindowActive: Boolean,
    ): Boolean =
        if (appActive) {
            appSettled && !isDeferred(thermallyStressed, lowPowerMode, allowLowPowerCompression)
        } else {
            backgroundWindowActive
        }

    /**
     * True when device stress currently defers FOREGROUND compression: thermally stressed (always), or
     * in Low Power Mode without the user opt-in. Drives the engine's resume pump — when this falls to
     * `false` with work still pending, deferred CBZ work is re-driven.
     */
    fun isDeferred(
        thermallyStressed: Boolean,
        lowPowerMode: Boolean,
        allowLowPowerCompression: Boolean,
    ): Boolean = thermallyStressed || (lowPowerMode && !allowLowPowerCompression)

    /**
     * True when the deferral is specifically the user-overridable Low Power Mode block (and NOT a
     * thermal block). Drives the "paused (Low Power Mode)" notification + Details row: a thermal defer
     * is transient (the device cools) and keeps the existing "Finalizing…" affordance, whereas a
     * Low-Power defer can persist for the whole session and must never look like an endless load.
     */
    fun isLowPowerDeferred(
        thermallyStressed: Boolean,
        lowPowerMode: Boolean,
        allowLowPowerCompression: Boolean,
    ): Boolean = !thermallyStressed && lowPowerMode && !allowLowPowerCompression
}
