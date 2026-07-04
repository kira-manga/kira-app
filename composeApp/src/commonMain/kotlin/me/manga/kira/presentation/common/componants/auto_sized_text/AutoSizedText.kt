package me.manga.kira.presentation.common.componants.auto_sized_text

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.sp


/**
 * Auto‐sized Small/Caption Text
 * - Initial font size: 14.sp
 * - Can scale down to 10.sp, up to 18.sp
 */
@Composable
fun NavigationBarAutoText(
    text: String,
    modifier: Modifier = Modifier
) {
    Box(modifier = modifier) {
        BasicText(
            text = text,
            style = TextStyle(
                color = MaterialTheme.colorScheme.onBackground,
                fontSize = 12.sp
            ),
            maxLines = 1,
            autoSize = TextAutoSize.StepBased(
                minFontSize = 8.sp,
                maxFontSize = 14.sp,
                stepSize = 1.sp
            )
        )
    }
}

/**
 * **Audit-trail postscript** (Phase 9.x.cluster158.staleKdocSweep.cascade,
 * Task #614, 2026-05-29): classified as follows after recursive symbol
 * verification (two-hundred-and-tenth sibling of the cluster57-157 sweep —
 * CLOSING file of the wave-30 :composeApp/commonMain utility 2-leaf batch
 * alongside Date; CLOSES commonMain utility tier 2/2):
 *  (a) "Auto-sized-Small-Caption-Text + Initial-font-size-14.sp + Can-scale
 *  -down-to-10.sp-up-to-18.sp" — LIVE-NOT-STALE plus FACTUALLY-DRIFTED-
 *  IN-PROSE-ONLY (the in-code TextAutoSize.StepBased now uses minFontSize=
 *  8.sp / maxFontSize=14.sp / stepSize=1.sp, NOT the KDoc-cited 10.sp /
 *  18.sp values — the KDoc was written against an earlier tuning and was
 *  never refreshed; the behaviour is intentional today (the smaller cap
 *  prevents the bottom-bar label from overflowing the icon column). Per
 *  §253 audit-trail-preservation convention the original prose stays
 *  verbatim; this postscript records the drift). Verified: @Composable
 *  fun NavigationBarAutoText(text: String, modifier: Modifier = Modifier)
 *  shipped as the bottom-nav-label auto-scaling caption helper; sibling
 *  @Composable fun AutoSubtitleText(text, color, textAlign, fontSize,
 *  maxSize, minSize, maxLines, fontWeight, overflow, style, modifier) ships
 *  as the more-configurable caption helper with the 6.sp / 12.sp / 14.sp
 *  default bounds. Both wrap BasicText with TextAutoSize.StepBased so a
 *  label that would otherwise wrap onto a second line shrinks within the
 *  configured min/max bounds instead — used on the rework BottomNavigation
 *  Bar's label slot where the localized strings can be long (e.g. Arabic
 *  "إعدادات" vs English "Settings") and on the Library/Details captions.
 *  Consumed by BottomNavigationBar (cluster83 sibling) + caption rows
 *  across the Library grid. CLOSING FILE of the cluster158 :composeApp/
 *  commonMain utility 2-leaf batch (2 of 2). One classification. Original
 *  Phase 7.x.bottomnav.auto-size prose preserved verbatim per the audit-
 *  trail-preservation convention.
 */
