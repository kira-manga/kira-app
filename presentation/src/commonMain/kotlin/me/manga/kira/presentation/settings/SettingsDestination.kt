package me.manga.kira.presentation.settings

/**
 * Identifies which Settings-hub navigation row the user tapped.
 *
 * Phase 7.x.settings.foundation rework. The Settings hub has 6 nav rows that each route to an
 * existing rework screen. The presentation layer doesn't know about `:composeApp`'s [Screen]
 * sealed type or `NavController`, so the VM emits a [SettingsEffect.NavigateTo] carrying one
 * of these abstract identifiers; the `:composeApp` route adapter
 * (`SettingsReworkScreenRoute`) maps each to the concrete `Screen.<X>Rework` destination.
 *
 * **Why an enum rather than a `String` route key**: contract §6 OCP — adding a future
 * destination is a new enum variant; the `:ui` composable's exhaustive `when` for icon/label
 * lookup, the `:composeApp` adapter's exhaustive `when` for nav-route mapping, and the VM's
 * `OnNavigate` handler all flag the missing case at compile time. A string-keyed approach
 * would only fail at runtime if a route mapping was missed.
 *
 * **Foundation set + downloads extension** (7 destinations):
 *  - [THEME] — rework Theme picker (tri-state + PureBlack toggle).
 *  - [STATISTICS] — rework Statistics screen.
 *  - [LANGUAGE] — rework Language picker.
 *  - [ABOUT] — rework About screen.
 *  - [COMPLAINT] — rework Feedbacks-and-Complaints screen (user-side). Admin routing is the
 *    `:composeApp` adapter's decision via `Admin.isAdmin` — same posture as the legacy
 *    `SettingsScreen.kt:272-278` `if (Admin.isAdmin) ComplaintAdmin else Complaint`. The
 *    presentation layer doesn't know about Admin state.
 *  - [WHATSNEW] — rework What's-new screen.
 *  - [DOWNLOADS] — bridges to the LEGACY `Screen.DownloadsScreen` (Phase 7.x.settings.downloads).
 *    There is no rework Downloads screen yet — porting it is its own future
 *    `Phase 7.x.downloads` slice. The Settings-hub→Downloads transition flows through the
 *    legacy screen until that slice lands; same parallel-routes posture every rework slice has
 *    used. The `:composeApp` adapter's exhaustive `when` maps DOWNLOADS → `Screen.DownloadsScreen`
 *    (legacy nav target).
 *
 * **Why DOWNLOADS bridges to the legacy screen and not a rework counterpart**: the rework
 * Downloads screen does not exist yet — neither in `:ui`, nor in `:presentation`, nor as a
 * `Screen.DownloadsRework` route. Adding a placeholder rework screen would require a full
 * MVI slice (DownloadsRepository / use cases / VM / composable / Koin module / nav route) for
 * a screen the user already has a working legacy version of. Bridging via the existing
 * `Screen.DownloadsScreen` honours the strangler-fig posture (legacy route stays put; the
 * rework hub merely points to it) and the standing 5-files-per-commit cap.
 *
 * **Why these and not the legacy 9** (ReadingMode dialog / Feedback dialog / Help are NOT
 * in this set): ReadingMode + Feedback are in-place dialogs (driven by separate intents +
 * state flags, not nav routes); Help is a no-op `onClick = null` placeholder in the legacy
 * too. The corresponding rework variants live as additive intent variants on
 * [SettingsIntent].
 *
 * Contract §6 SRP: this enum has ONE rule — "name the Settings-hub navigation targets the
 * foundation+downloads slice exposes". No URL semantics, no nav arguments — destinations are
 * nullary (the targets themselves are terminal screens with no nav args).
 *
 * **Audit-trail postscript** (Phase 9.x.cluster28.staleKdocSweep.cascade,
 * Task #484, 2026-05-28): two fulfilled-forecast / stale citations
 * appear above:
 *  - Line 25 ([COMPLAINT] KDoc, "same posture as the legacy
 *    `SettingsScreen.kt:272-278` `if (Admin.isAdmin) ComplaintAdmin
 *    else Complaint`"). STALE-SYMBOL-REFERENCE — Phase 9.x.settings_
 *    about.legacyui.retire (§354, "delete 11-file legacy Settings+
 *    About orphan chain") DELETED the cited legacy `:composeApp/.../
 *    features/settings/ui/screens/SettingsScreen.kt`. Glob returns
 *    NO MATCHES. The admin-branch-as-`:composeApp`-concern rationale
 *    stands on its own merits (the presentation layer doesn't know
 *    about Admin state regardless of legacy lineage); the citation
 *    is historical record of the design lineage. The rework
 *    `SettingsReworkScreenRoute` adapter's exhaustive `when` is the
 *    LIVE realization of the if-Admin-then-ComplaintAdminRework-else-
 *    ComplaintRework branch.
 *  - Lines 28-32 + 35-41 ([DOWNLOADS] KDoc forecasts: "There is no
 *    rework Downloads screen yet — porting it is its own future
 *    `Phase 7.x.downloads` slice" + "The Settings-hub→Downloads
 *    transition flows through the legacy screen until that slice
 *    lands" + "Bridging via the existing `Screen.DownloadsScreen`
 *    honours the strangler-fig posture (legacy route stays put; the
 *    rework hub merely points to it)"). PARTIALLY-FULFILLED-INVERSION
 *    — Phase 7.x.downloads (§§276-281, full 6-slice campaign) LANDED
 *    the rework Downloads MVI / composable / Koin module / nav route
 *    + actions slice. Phase 7.x.downloads.swap (§295) then re-pointed
 *    `Screen.DownloadsScreen` to render the rework `DownloadsScreen`
 *    backed by the rework `DownloadsViewModel`; Phase 9.x.downloads.
 *    legacyui.retire (§352) DELETED the legacy Downloads screen +
 *    components. HOWEVER — the `Screen.DownloadsScreen` route key
 *    STILL EXISTS in `Screen.kt` (callers untouched per the standard
 *    keep-key-flip-adapter strangler-fig posture), so the Settings-
 *    hub→DOWNLOADS bridge via `Screen.DownloadsScreen` STAYS valid
 *    (the `:composeApp` adapter's `when` still maps DOWNLOADS →
 *    `Screen.DownloadsScreen`; that route now renders the rework UI
 *    instead of the deleted legacy UI). The 5-files-per-commit cap
 *    rationale stands on its own merits; the route-bridge mechanism
 *    survived the rework slice landing. The SRP + OCP + nullary-
 *    destinations sub-sections all stand on their own merits past
 *    the §§295 + 352 fulfilled landings. The [SettingsDestination]
 *    enum remains LIVE as the canonical Settings-hub nav-identifier
 *    ADT consumed by [SettingsEffect.NavigateTo] + the
 *    `SettingsReworkScreenRoute` adapter's exhaustive `when`.
 *
 * Original §253-era prose preserved verbatim per the audit-trail-
 * preservation convention — the citations are historical record of
 * the design lineage including the deferred-rework-slice + deferred-
 * route-swap forecasts that were subsequently fulfilled across
 * §§276-281 + 295 + 352 + 354.
 */
enum class SettingsDestination {
    SOURCE_MANAGEMENT,
    SOURCE_CATALOG,
    THEME,
    STATISTICS,
    LANGUAGE,
    ABOUT,
    COMPLAINT,
    WHATSNEW,
    DOWNLOADS,
    CRASH_DIAGNOSTICS,

    /**
     * Backup & restore (feature/backup) — full-library export + merge-import. The `:composeApp`
     * adapter maps this to `Screen.BackupRework()` with an empty scope (full-library mode).
     */
    BACKUP,
}
