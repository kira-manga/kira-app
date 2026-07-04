package me.manga.kira.platform.activity

import android.app.Activity
import java.lang.ref.WeakReference

/**
 * Process-wide singleton that tracks the current foreground [Activity] behind a [WeakReference],
 * so the Phase 5.z third-party-service facades (`AndroidAppUpdateClient`, `AndroidInAppReviewClient`,
 * `AndroidConsentFlowClient`, `AndroidAdProvider`) can obtain an `Activity` at show-time without
 * leaking it.
 *
 * This is the concrete backing store the [ForegroundActivityProvider] typealias documents as
 * "usually something like `{ MainActivity.current?.get() }`": each facade is bound in
 * `PlatformModule.android.kt` with `activityProvider = { ActivityHolder.current }`, and the host
 * (`:app`'s `MyApp`) keeps [current] up to date via
 * `Application.registerActivityLifecycleCallbacks` — see [activityLifecycleCallbacks].
 *
 * Placement rationale: it lives in `:platform` androidMain next to [ForegroundActivityProvider]
 * because both `:shared` (which binds the facades in `PlatformModule.android.kt`) and `:app`
 * (which registers the lifecycle callbacks) depend on `:platform` (`:shared` via `api(project(
 * ":platform"))`, transitively re-exported to `:app`), so a single declaration is visible to both
 * without duplication.
 *
 * Thread-safety: writes happen on the main thread from the `ActivityLifecycleCallbacks`; reads
 * happen from arbitrary coroutine dispatchers inside the facades. The backing field is marked
 * `@Volatile` so a stale `WeakReference` is never observed across threads.
 */
object ActivityHolder {

    @Volatile
    private var ref: WeakReference<Activity>? = null

    /** The current foreground [Activity], or `null` if none is resumed (or it has been GC'd). */
    val current: Activity?
        get() = ref?.get()

    /** Records [activity] as the current foreground Activity. Called from `onActivityResumed`. */
    fun set(activity: Activity) {
        ref = WeakReference(activity)
    }

    /**
     * Clears the held reference, but only if it still points at [activity]. Guarding on identity
     * avoids a paused/destroyed Activity wiping out a newer one that already resumed (config-change
     * and Activity-to-Activity transitions interleave `onPause(old)` after `onResume(new)`).
     */
    fun clear(activity: Activity) {
        if (ref?.get() === activity) {
            ref = null
        }
    }
}
