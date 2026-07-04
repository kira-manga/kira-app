package me.manga.kira.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

/**
 * Yami shape tokens.
 *
 * **Behavior preservation:** values copied verbatim from legacy `composeApp/.../theme/Theme.kt`
 * (commit e0466ce baseline). [Shapes.extraLarge] is intentionally `0.dp` — full-bleed surfaces
 * like the reader rely on this. Do NOT change without a paired UX decision.
 *
 * **Audit-trail postscript** (Phase 9.x.cluster95.staleKdocSweep.cascade,
 * Task #551, 2026-05-28): the 2-claim shape-token manifest above is
 * classified as follows after recursive symbol verification across
 * the KMP graph (thirty-sixth sibling of the cluster57-94 sweep —
 * sibling of cluster94's KiraColors in the `:ui/theme/` cluster):
 *  (a) "values copied verbatim from legacy `composeApp/.../theme/
 *  Theme.kt` (commit e0466ce baseline)" — LIVE-NOT-STALE. The legacy
 *  `Shapes` block at `composeApp/src/commonMain/kotlin/me/manga/
 *  yamiapk/theme/Theme.kt:85-91` hosts identical `RoundedCornerShape`
 *  values: extraSmall=4.dp, small=8.dp, medium=12.dp, large=16.dp,
 *  extraLarge=0.dp. L15-19 of THIS file mirror those five entries
 *  byte-for-byte. Behavior preservation seam intact.
 *  (b) "[Shapes.extraLarge] is intentionally `0.dp` — full-bleed
 *  surfaces like the reader rely on this" — LIVE-NOT-STALE. L19
 *  realization (`extraLarge = RoundedCornerShape(0.dp)`); legacy
 *  Theme.kt L90 preserves the same `0.dp` value as the deliberate
 *  full-bleed choice. The UX-gate caveat ("Do NOT change without a
 *  paired UX decision") remains the durable contract.
 *  Two LIVE-NOT-STALE classifications STAND on their own merits as
 *  a faithful M3 shape-token manifest. Original Phase 7-era prose
 *  preserved verbatim per the audit-trail-preservation convention.
 */
// Redesign (2026-06): radii bumped for the new softer, more premium component language
// (cards/sheets/buttons round more). extraLarge stays 0.dp — the reader relies on full-bleed.
internal val KiraShapes: Shapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(0.dp),
)
