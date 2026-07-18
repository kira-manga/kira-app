package me.manga.kira.admin

/**
 * Admin runtime toggles consumed by source/destination switching logic.
 *
 * ## C1 — fail-closed, debug-only admin (2026-07-03)
 *
 * [isAdmin] defaults to **false** (the safe state) and is flipped to `true` only by the host
 * bootstraps in debug/dev builds:
 *  - **Android** — `MyApp.onCreate`: `Admin.isAdmin = BuildConfig.DEBUG`.
 *  - **iOS** — `bootstrapIosKoin()` (`:composeApp` iosMain): `Platform.isDebugBinary` — the Xcode
 *    Debug configuration embeds the Debug framework; Release/TestFlight/App Store builds get
 *    `false`. (Same signal `:data:remote`'s `isHttpLoggingEnabled` iOS actual uses.)
 *  - **Desktop** — `Main.kt`: JVM assertions enabled (`-ea`, the same dev-run probe the Desktop
 *    log floor uses) OR the explicit `-Dkira.debug=true` system property; packaged distributions
 *    set neither.
 *
 * One thing reads this flag today, so release users never see it:
 *  1. the admin complaint console — `SettingsRoute` picks `Screen.ComplaintAdminRework` vs
 *     `Screen.ComplaintRework`, and the route registration in `App.kt` re-checks defensively.
 *
 * (A second consumer — the remote source-registry feed's `/dev/source`-when-admin vs
 * `/source/35` split — was DELETED in SourceRegistry retirement Phase 6 (2026-07-04) together
 * with the whole endpoint path: the bundled JSON config document is the single authority for
 * source metadata/lifecycle. See `SOURCE_REGISTRY_RETIREMENT_PLAN.md`.)
 *
 * Because a host that forgets to assign gets `false`, a new platform fails CLOSED — pinned by
 * `AdminDefaultsTest`. A production admin mechanism (Firebase custom claims / allow-list) is
 * deliberately NOT implemented. **C2 — Firestore security rules remain a public-release
 * blocker**: hiding the console in release builds is not authorization, and the repository does
 * not contain the deployed Firestore rules. The complaint feature may remain enabled for internal
 * testing, but it must be secured server-side (or disabled) before a public build ships.
 *
 * Migration notes (Phase 8.13 batch B):
 *  - The source `Admin` object additionally exposed
 *      `var testingMode by mutableStateOf(false)` (Compose runtime; not available in
 *      `:shared/commonMain`) and a `logLong(tag, message, level)` helper that delegated to
 *      `android.util.Log`. Both are UI-layer/Android-only concerns and live in the composeApp
 *      module — they'll land when the settings UI is ported in Phase 10.
 *
 * Phase 9.x.updatesourcesrepository.retire (Task #387): the prior reference to
 * `UpdateSourcesRepository`'s dev-vs-prod source-list URL as the sole repository-layer reacher of
 * `isAdmin` is dropped — `UpdateSourcesRepository` is itself now retired (orphan Koin `single`
 * with zero `get<UpdateSourcesRepository>()` reachers in the source tree). `isAdmin` remains
 * LIVE: the `:composeApp` Settings hub adapters (`SettingsReworkScreenRoute.kt:100`,
 * `SettingsRoute.kt:121`) consult it to gate `Screen.ComplaintAdminRework` vs
 * `Screen.ComplaintRework` — same posture as the legacy `SettingsScreen.kt:272-278`'s
 * `if (Admin.isAdmin) ComplaintAdmin else Complaint` branch.
 *
 * **Audit-trail postscript** (Phase 9.x.cluster5.staleKdocSweep.cascade,
 * Task #460, 2026-05-28): a stale citation into the §354-retired legacy
 * `composeApp/.../features/settings/ui/screens/SettingsScreen.kt`
 * appears above:
 *  - Line 19 (LIVE-consumer affordance-parity bullet): "same posture as
 *    the legacy `SettingsScreen.kt:272-278`'s `if (Admin.isAdmin)
 *    ComplaintAdmin else Complaint` branch".
 * The legacy
 * `composeApp/.../features/settings/ui/screens/SettingsScreen.kt` was
 * retired in Phase 9.x.settings.legacy_retire (§354 sweep, commit
 * `5cc42d2` "(1/2): delete 5 orphan settings UI files"); verified by a
 * filesystem check returning zero hits for that path. The admin-gate
 * affordance-parity rationale (admin users → admin dashboard; non-
 * admins → user-side Feedback Manager) stands on its own merits — the
 * Settings-hub routing decision is documented inline in the
 * [me.manga.kira.navigation.routes.SettingsRoute] / [me.manga.
 * yamiapk.navigation.routes.SettingsReworkScreenRoute] KDocs (both
 * postscripted under Task #460), and `Admin.isAdmin` remains LIVE with
 * those two route adapters as its sole `:composeApp`-side reachers.
 * Original §253-era prose preserved verbatim per the audit-trail-
 * preservation convention — the citation is historical record of the
 * design lineage; the admin-gate branch continues to surface correctly
 * through both Settings-hub adapters past the legacy retire.
 */
object Admin {
    // C1: fail-closed default — only the host bootstraps flip this, and only in debug builds.
    var isAdmin: Boolean = false

    /**
     * Phase 7.x.settings.testingmode — admin-only "Testing Mode" runtime flag, restored from the
     * legacy source `Admin` object (which held `var testingMode by mutableStateOf(false)`). The
     * Compose-runtime `mutableStateOf` wrapper lived in the UI layer; here it is a plain `var`
     * (commonMain can't reference `androidx.compose.runtime`). The rework Settings hub's
     * admin-gated switch reads + flips this value via the `:composeApp` route adapter; the `:ui`
     * row mirrors it into local Compose state for immediate visual feedback. Defaults `false`
     * (parity with the legacy source default).
     */
    var testingMode: Boolean = false
}
