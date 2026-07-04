package me.manga.kira.ui.complaint

import androidx.compose.runtime.Composable
import me.manga.kira.domain.model.complaint.ComplaintStatus
import me.manga.kira.ui.generated.resources.Res
import me.manga.kira.ui.generated.resources.status_closed
import me.manga.kira.ui.generated.resources.status_in_progress
import me.manga.kira.ui.generated.resources.status_not_planned
import me.manga.kira.ui.generated.resources.status_open
import me.manga.kira.ui.generated.resources.status_pinned
import me.manga.kira.ui.generated.resources.status_planned
import me.manga.kira.ui.generated.resources.status_resolved
import me.manga.kira.ui.generated.resources.status_unknown
import org.jetbrains.compose.resources.stringResource

/**
 * `:ui`-side display-name lookup for the rework's `:domain` [ComplaintStatus] enum.
 *
 * Phase 7.x.complaint.displaynames corrective slice: prior to this slice, every rework consumer
 * rendered `status.name` directly — exposing raw Kotlin enum identifiers (`OPEN`,
 * `IN_PROGRESS`, ...) to the user. The legacy `composeApp/.../presentation/features/complaint/
 * model/ComplaintStatusDisplay.kt` exposes a `.displayName()` Composable extension that maps
 * each variant to a human-readable English string. UP-3 localization lift: each variant
 * resolves through `stringResource(Res.string.status_*)` against the `:ui` compose-resources
 * catalog, reusing the legacy status keys so the hand-authored Arabic translations apply
 * verbatim (see [ComplaintScreen]'s KDoc).
 *
 * Key sources (reused from the legacy complaint catalog):
 *  - `OPEN` → `status_open`, `IN_PROGRESS` → `status_in_progress`,
 *    `RESOLVED` → `status_resolved`, `PLANNED` → `status_planned`,
 *    `PINNED` → `status_pinned`, `NOT_PLANNED` → `status_not_planned`.
 *  - `CLOSED` → `status_closed` ("Closed").
 *  - `UNKNOWN` → `status_unknown` ("Unknown").
 *
 * Contract §6 SRP: one rule — "map a rework [ComplaintStatus] variant to its display string".
 * No side effects, no derivation beyond the `when` match.
 *
 * Contract §6 OCP: exhaustive `when` over a closed enum — adding a new variant to
 * [ComplaintStatus] forces this `when` to update (compile-time enforcement, no silent
 * fall-through).
 *
 * Contract §6 DIP: depends only on the `:domain` enum; no `:data` / `:shared` reach.
 *
 * **Audit-trail postscript** (Phase 9.x.complaint.staleKdocSweep.cascade,
 * Task #452, 2026-05-28): the cited legacy `composeApp/.../presentation/features/
 * complaint/model/ComplaintStatusDisplay.kt` (referenced by name at the "Phase
 * 7.x.complaint.displaynames corrective slice" paragraph, lines 11-17, and by
 * line-anchored citation `ComplaintStatusDisplay.kt:24-31` at line 21) was
 * retired in Phase 9.x.complaint.legacycomponents.retire (§370); verified by
 * a filesystem check returning zero hits for that path. The mirror-the-
 * legacy-fallbacks-verbatim rationale stands on its own merits — the inline
 * literal map is independent of which legacy file originally documented the
 * eight English strings, and the Phase-10 deferred-i18n posture continues
 * unchanged. Original §253-era prose preserved verbatim per the audit-trail-
 * preservation convention — the line-anchored citation is historical record
 * of the design lineage; the displayName extension continues to project
 * correctly through the legacy retire.
 */
@Composable
fun ComplaintStatus.displayName(): String = when (this) {
    ComplaintStatus.OPEN -> stringResource(Res.string.status_open)
    ComplaintStatus.IN_PROGRESS -> stringResource(Res.string.status_in_progress)
    ComplaintStatus.RESOLVED -> stringResource(Res.string.status_resolved)
    ComplaintStatus.CLOSED -> stringResource(Res.string.status_closed)
    ComplaintStatus.PLANNED -> stringResource(Res.string.status_planned)
    ComplaintStatus.PINNED -> stringResource(Res.string.status_pinned)
    ComplaintStatus.UNKNOWN -> stringResource(Res.string.status_unknown)
    ComplaintStatus.NOT_PLANNED -> stringResource(Res.string.status_not_planned)
}
