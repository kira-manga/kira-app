package me.manga.kira.ui.complaint

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import me.manga.kira.domain.model.complaint.ComplaintStatus

/**
 * Per-status colored chip for the complaint surfaces.
 *
 * Phase 7.x.complaint.statuschip rework. Ports the legacy
 * `composeApp/.../presentation/features/complaint/ui/components/ComplaintComponents.kt:55`
 * `StatusChip` composable verbatim — same RoundedCornerShape(16.dp) Surface, same
 * horizontal=12.dp / vertical=6.dp text padding, same labelMedium/Medium-weight typography,
 * same per-status (containerColor, contentColor) pairs.
 *
 * **Per-status color mapping (matches legacy `ComplaintComponents.kt:56-65` verbatim)**:
 *  - OPEN → primaryContainer / onPrimaryContainer (interpreted as "actionable")
 *  - IN_PROGRESS → tertiaryContainer / onTertiaryContainer ("in-flight")
 *  - RESOLVED → secondaryContainer / onSecondaryContainer ("settled positively")
 *  - CLOSED → errorContainer / onErrorContainer ("settled negatively")
 *  - PLANNED → surfaceVariant / onSurfaceVariant ("queued / muted")
 *  - PINNED → Color.Black / Color.White (admin-FAQ entries — legacy-verbatim
 *    theme-independent styling; visually distinct from M3 status semantics)
 *  - UNKNOWN → Color.Black / Color.White (same as PINNED — graceful unrecognized-
 *    state fallback)
 *  - NOT_PLANNED → Color.Gray / Color.White (legacy distinguishes from
 *    PINNED/UNKNOWN with grey instead of black; preserved verbatim)
 *
 * Theme-independent `Color.Black` / `Color.White` for PINNED/UNKNOWN/NOT_PLANNED is a
 * deliberate parity choice — the legacy uses these exact constants so the dark-mode
 * appearance matches the legacy's dark-mode appearance. Phase 10 i18n / theme-token lift
 * may revisit this as `MaterialTheme.colorScheme.inverseSurface` / `inverseOnSurface` if
 * the dark-mode contrast becomes a UX bug, but only as a joint legacy + rework change.
 *
 * **`internal` visibility** — call sites are the same-package `ComplaintScreen.ComplaintRow`,
 * the same-package `ComplaintActionDialog.ComplaintPreviewCard`, and the sub-package
 * `me.manga.kira.ui.complaint.admin.AdminComplaintScreen.AdminComplaintRow`. Kotlin's
 * `internal` is module-wide so all three resolve without a visibility lift. Matches
 * `formatComplaintTimestamp`'s posture (§115.5).
 *
 * **Why a composable (not a bare color extension)** — the legacy is a full Surface-pill
 * widget, not a bare-text color. The rework's pre-statuschip posture rendered a bare Text in
 * `MaterialTheme.colorScheme.primary` uniformly across statuses — that diverged from the
 * legacy on TWO axes (no pill chrome, no per-status color). Porting both axes in one slice
 * restores full visual parity. The cost is one new composable shape, not two separate
 * extensions.
 *
 * **Why not in `:domain`** — color is a UI concern (depends on MaterialTheme and platform-
 * specific theme tokens). `:domain` is platform-agnostic value types only. Same reasoning
 * as `ComplaintStatus.displayName()` / `formatComplaintTimestamp` placement (§107 / §110 /
 * §114.5).
 *
 * **Why not in `:presentation`** — the color is a pure projection at render time. Putting
 * it in the VM would force every state emission to carry pre-resolved colors, breaking the
 * MaterialTheme inheritance chain (the same complaint renders differently in light vs dark
 * theme; the VM has no theme awareness). Same reasoning as `formatComplaintTimestamp` (§114.5).
 *
 * **SRP (contract §6)** — one rule per declaration: [chipColors] resolves status →
 * (container, content) color pair; [ComplaintStatusChip] renders the pill.
 *
 * **OCP (contract §6)** — closed under the existing 8 status variants (matches the
 * domain enum). Adding a new variant requires extending [chipColors]'s `when` and updating
 * the domain enum — the call-site composables don't change.
 *
 * **DIP (contract §6)** — depends only on `:domain ComplaintStatus` and Compose Material3
 * theme primitives. No `:data` / `:presentation` / `:shared` reach.
 *
 * **Audit-trail postscript** (Phase 9.x.complaint.staleKdocSweep.cascade,
 * Task #452, 2026-05-28): the two line-anchored citations into legacy
 * `composeApp/.../presentation/features/complaint/ui/components/
 * ComplaintComponents.kt` are both stale — at line 20 (cites `:55` for the
 * `StatusChip` composable) and at line 25 (cites `:56-65` for the eight-
 * variant color map). That legacy file was retired in
 * Phase 9.x.complaint.legacycomponents.retire (§370); verified by a
 * filesystem check returning zero hits for that path. The visual-parity
 * rationale (matched-verbatim Surface chrome + per-status M3 container/
 * content color pairs + theme-independent Black/White for PINNED/UNKNOWN/
 * NOT_PLANNED) stands on its own merits — the eight color pairings are
 * documented exhaustively inline above and are independent of which legacy
 * file originally carried the mapping. Phase 10's planned i18n/theme-token
 * lift remains the canonical next opportunity to revisit the constants
 * collectively. Original §253-era prose preserved verbatim per the audit-
 * trail-preservation convention — the line-anchored citations are historical
 * record of the design lineage; the chip continues to render correctly
 * through the legacy retire.
 */
@Composable
@ReadOnlyComposable
internal fun chipColors(status: ComplaintStatus): Pair<Color, Color> = when (status) {
    ComplaintStatus.OPEN ->
        MaterialTheme.colorScheme.primaryContainer to MaterialTheme.colorScheme.onPrimaryContainer
    ComplaintStatus.IN_PROGRESS ->
        MaterialTheme.colorScheme.tertiaryContainer to MaterialTheme.colorScheme.onTertiaryContainer
    ComplaintStatus.RESOLVED ->
        MaterialTheme.colorScheme.secondaryContainer to MaterialTheme.colorScheme.onSecondaryContainer
    ComplaintStatus.CLOSED ->
        MaterialTheme.colorScheme.errorContainer to MaterialTheme.colorScheme.onErrorContainer
    ComplaintStatus.PLANNED ->
        MaterialTheme.colorScheme.surfaceVariant to MaterialTheme.colorScheme.onSurfaceVariant
    ComplaintStatus.PINNED -> Color.Black to Color.White
    ComplaintStatus.UNKNOWN -> Color.Black to Color.White
    ComplaintStatus.NOT_PLANNED -> Color.Gray to Color.White
}

@Composable
internal fun ComplaintStatusChip(
    status: ComplaintStatus,
    modifier: Modifier = Modifier,
) {
    val (container, content) = chipColors(status)
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = container,
        modifier = modifier,
    ) {
        Text(
            text = status.displayName(),
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            style = MaterialTheme.typography.labelMedium,
            color = content,
            fontWeight = FontWeight.Medium,
        )
    }
}

/**
 * High-saturation contrasting (background, content) pair for a [ComplaintStatus] — port of native
 * `ClosureReasonExtensions.kt:58-93` `ComplaintStatus.getColorWithContrast()` (GAP-CMP-U8).
 *
 * Native intentionally renders the action-dialog preview status badge in a vivid, theme-
 * independent palette (OPEN→cyan, IN_PROGRESS→blue, RESOLVED→green, CLOSED→blue-grey,
 * PLANNED→purple, PINNED→orange, UNKNOWN→red, NOT_PLANNED→light-green — all on white text)
 * distinct from the list card's M3-container [chipColors]. This keeps the dialog preview badge
 * visually louder than the list chip. Values copied verbatim from native.
 */
private fun statusContrastColors(status: ComplaintStatus): Pair<Color, Color> = when (status) {
    ComplaintStatus.OPEN -> Color(0xFF00BCD4) to Color.White
    ComplaintStatus.IN_PROGRESS -> Color(0xFF2196F3) to Color.White
    ComplaintStatus.RESOLVED -> Color(0xFF4CAF50) to Color.White
    ComplaintStatus.CLOSED -> Color(0xFF607D8B) to Color.White
    ComplaintStatus.PLANNED -> Color(0xFF9C27B0) to Color.White
    ComplaintStatus.PINNED -> Color(0xFFFF9800) to Color.White
    ComplaintStatus.UNKNOWN -> Color(0xFFF44336) to Color.White
    ComplaintStatus.NOT_PLANNED -> Color(0xFF8BC34A) to Color.White
}

/**
 * Vivid status badge for the action-dialog preview cards — port of native
 * `ComplaintActionDialog.kt:630-642` `ComplaintPreviewCard` status badge (GAP-CMP-U8).
 *
 * Differs from [ComplaintStatusChip] on two axes mirroring native: the high-saturation
 * [statusContrastColors] palette (not the M3 container pair) and the tighter
 * RoundedCornerShape(8.dp) (not 16.dp). Used by the user-side + admin-side dialog
 * [ComplaintPreviewCard]s so the dialog badge matches native's louder preview styling.
 */
@Composable
internal fun ComplaintStatusBadge(
    status: ComplaintStatus,
    modifier: Modifier = Modifier,
) {
    val (background, text) = statusContrastColors(status)
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = background,
        modifier = modifier,
    ) {
        Text(
            text = status.displayName(),
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            style = MaterialTheme.typography.labelSmall,
            color = text,
            fontWeight = FontWeight.Medium,
        )
    }
}
