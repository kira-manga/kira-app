package me.manga.kira.ui.home.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import me.manga.kira.domain.model.home.SourceTab
import me.manga.kira.ui.common.LocalSourceIconResolver
import me.manga.kira.ui.common.RemoteSourceIcon
import me.manga.kira.ui.common.SourceIconResolution
import org.jetbrains.compose.resources.painterResource
import me.manga.kira.ui.components.KiraIconButton
import me.manga.kira.ui.components.KiraIcons
import me.manga.kira.ui.theme.KiraBrand

/**
 * Horizontal source-tab strip + a trailing edit-sources action (Home redesign 2026-06).
 *
 * Redesigned as **segmented coral pills**: the selected source is a vivid coral→amber gradient pill
 * with white text; unselected sources are neutral `surfaceVariant` pills with muted text. A hairline
 * divider separates the strip from the trailing edit-sources pencil so "manage sources" reads as a
 * distinct, discoverable action (design-review v2 fix). Public signature is unchanged from the prior
 * `PrimaryScrollableTabRow` implementation — only the internals were restyled.
 *
 * @param iconForTab optional leading per-source icon slot (the route adapter passes a source-aware
 *   painter; default renders nothing).
 */
@Composable
fun SourceTabsRow(
    tabs: List<SourceTab>,
    activeTabIndex: Int,
    onTabSelected: (Int) -> Unit,
    onEditSources: () -> Unit,
    modifier: Modifier = Modifier,
    showNewBadge: Boolean = false,
    newBadgeLabel: String? = null,
    editContentDescription: String? = null,
    iconForTab: (@Composable (SourceTab, Boolean) -> Unit)? = null,
) {
    if (tabs.isEmpty()) return
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(56.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LazyRow(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(horizontal = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(9.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            itemsIndexed(tabs, key = { _, t -> t.api + "_" + t.language }) { index, tab ->
                val selected = index == activeTabIndex
                val pillModifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .then(
                        if (selected) {
                            Modifier.background(KiraBrand.Gradient)
                        } else {
                            Modifier.background(MaterialTheme.colorScheme.surfaceVariant)
                        },
                    )
                    .clickable { onTabSelected(index) }
                    .padding(horizontal = 16.dp, vertical = 9.dp)
                Row(
                    modifier = pillModifier,
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    // U1 (Home tab brand icons): prefer the per-source brand icon from the
                    // app-wide [LocalSourceIconResolver] (provided at the App root; same seam the
                    // Sources screens use) so each pill shows its real source icon — a packaged
                    // drawable, or a config-declared remote URL rendered via [RemoteSourceIcon].
                    // Falls back to the caller's [iconForTab] slot (the neutral glyph) when no icon
                    // is declared for this api or the remote icon can't load. Full-colour Image
                    // (not a tinted Icon) — brand marks keep their own palette on both the gradient
                    // (selected) and surfaceVariant pills.
                    val neutralGlyph: @Composable () -> Unit = { iconForTab?.invoke(tab, selected) }
                    when (val brandIcon = LocalSourceIconResolver.current(tab.api)) {
                        is SourceIconResolution.Packaged -> Image(
                            painter = painterResource(brandIcon.drawable),
                            contentDescription = null, // decorative — the pill text names the source
                            modifier = Modifier
                                .size(18.dp)
                                .clip(CircleShape),
                        )
                        is SourceIconResolution.Remote -> RemoteSourceIcon(
                            url = brandIcon.url,
                            modifier = Modifier
                                .size(18.dp)
                                .clip(CircleShape),
                            fallback = neutralGlyph,
                        )
                        SourceIconResolution.None -> neutralGlyph()
                    }
                    Text(
                        text = tab.displayName,
                        color = if (selected) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                    )
                }
            }
        }

        // Hairline divider so the edit-sources action reads as distinct from the source pills.
        Box(
            modifier = Modifier
                .padding(start = 4.dp, end = 2.dp)
                .width(1.dp)
                .height(26.dp)
                .background(MaterialTheme.colorScheme.outlineVariant),
        )

        Box(
            modifier = Modifier.padding(end = 8.dp),
            contentAlignment = Alignment.Center,
        ) {
            KiraIconButton(
                icon = KiraIcons.Edit,
                contentDescription = editContentDescription,
                onClick = onEditSources,
                tint = MaterialTheme.colorScheme.primary,
            )
            if (showNewBadge && newBadgeLabel != null) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .size(9.dp)
                        .clip(RoundedCornerShape(50))
                        .background(KiraBrand.Coral),
                )
            }
        }
    }
}
