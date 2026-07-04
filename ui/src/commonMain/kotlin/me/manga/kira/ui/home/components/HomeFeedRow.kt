package me.manga.kira.ui.home.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import me.manga.kira.domain.model.home.HomeChapterRef
import me.manga.kira.domain.model.home.HomeFeedItem
import me.manga.kira.ui.components.KiraCoverImage
import me.manga.kira.ui.components.KiraIcons

/**
 * Home-feed list row (Home redesign 2026-06).
 *
 * Airy surface card: a rounded 78×104 cover, the title, a muted `source · genre` meta line, up to 3
 * recent-chapter shortcut pills (the latest in coral-soft, older ones neutral), and a trailing
 * circular bookmark toggle (coral-soft when saved, inline spinner while a save is in flight). Replaces
 * the prior heavy-elevation Material slab with the new soft, content-forward language. Public
 * signature, click semantics (manga / chapter / save-toggle) and the saving spinner are unchanged.
 */
@Composable
fun HomeFeedRow(
    item: HomeFeedItem,
    isSaved: Boolean,
    onMangaClick: (HomeFeedItem) -> Unit,
    onChapterClick: (HomeFeedItem, HomeChapterRef) -> Unit,
    onSaveToggle: (HomeFeedItem) -> Unit,
    modifier: Modifier = Modifier,
    isSaving: Boolean = false,
    saveContentDescription: String? = null,
    savedContentDescription: String? = null,
    coverModel: ((HomeFeedItem) -> Any?)? = null,
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .clickable { onMangaClick(item) },
        shape = RoundedCornerShape(20.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.onSurface,
        ),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Row(
            // Top-aligned, not centered: the hard-sized 78×104 cover anchors the row height, and the text
            // column grows with the title (1–2 lines) + 0–3 chapter pills. Top alignment pins the
            // title/meta/pills to the SAME baseline on every card so the row reads consistently at
            // 0/1/2/3 chapters (center alignment floated the shorter side and shifted the title baseline).
            modifier = Modifier.fillMaxWidth().padding(11.dp),
            verticalAlignment = Alignment.Top,
        ) {
            // HARD-SIZED 78×104 cover in a fixed Box. KiraCoverImage internally applies fillMaxWidth(),
            // which overrode the caller's width(78.dp) (its resolution depends on the row's incoming
            // constraints, e.g. a LazyColumn's first item) and let the cover balloon to fill the row —
            // ballooning the whole card's height (the huge empty card seen on device). The fixed Box bounds
            // it deterministically; aspectRatio = null makes KiraCoverImage fill that box rather than
            // derive its own run-away height.
            Box(modifier = Modifier.size(width = 78.dp, height = 104.dp)) {
                KiraCoverImage(
                    coverUrl = item.coverUrl,
                    model = coverModel?.invoke(item),
                    contentDescription = item.title,
                    aspectRatio = null,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxSize(),
                )
            }
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 14.dp),
            ) {
                Text(
                    text = item.title,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Bold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                val meta = buildString {
                    append(item.api)
                    item.genres.firstOrNull()?.let { append(" · "); append(it) }
                }
                Text(
                    text = meta,
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 4.dp),
                )
                if (item.recentChapters.isNotEmpty()) {
                    // FlowRow, not Row: at normal widths all 3 pills sit on one line; on a very narrow
                    // card (≤~340dp) the 3rd cleanly wraps to a second line instead of being clipped
                    // mid-pill at the row's edge ("avoid clipped content").
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(7.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.padding(top = 10.dp),
                    ) {
                        item.recentChapters.take(3).forEachIndexed { i, chapter ->
                            // Sources often put the whole "Chapter 84" in the number field, which made the
                            // lead pill read the redundant "Ch Chapter 84" and the wide labels wrap to two
                            // lines / overflow with 3 pills. Normalize to the bare number so the pills stay
                            // compact (the latest is marked by colour, not the "Ch" word).
                            val n = chapterNumberLabel(chapter.number)
                            ChapterPill(
                                label = if (i == 0) "Ch $n" else n,
                                lead = i == 0,
                                onClick = { onChapterClick(item, chapter) },
                            )
                        }
                    }
                }
            }
            SaveToggle(
                isSaved = isSaved,
                isSaving = isSaving,
                contentDescription = if (isSaved) savedContentDescription else saveContentDescription,
                onClick = { onSaveToggle(item) },
            )
        }
    }
}

/**
 * Compact chapter-number label for the Home pills. Pulls the trailing number/decimal out of the source's
 * chapter-number string, so a source that labels its chapters "Chapter 84" / "الفصل 84" shows just "84"
 * (no redundant "Ch Chapter 84", no wide-label wrap/overflow). Non-numeric labels (e.g. "Extra") fall
 * back to the trimmed original.
 */
private val TRAILING_NUMBER = Regex("""\d[\d.]*$""")
internal fun chapterNumberLabel(raw: String): String {
    val trimmed = raw.trim()
    return TRAILING_NUMBER.find(trimmed)?.value ?: trimmed
}

@Composable
private fun ChapterPill(label: String, lead: Boolean, onClick: () -> Unit) {
    val bg = if (lead) {
        MaterialTheme.colorScheme.primary.copy(alpha = 0.13f)
    } else {
        MaterialTheme.colorScheme.surfaceVariant
    }
    val fg = if (lead) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
    Text(
        text = label,
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.Bold,
        color = fg,
        // Single line: a long source label must never wrap the pill to two lines (the device bug).
        maxLines = 1,
        softWrap = false,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(bg)
            .clickable { onClick() }
            .padding(horizontal = 11.dp, vertical = 6.dp),
    )
}

@Composable
private fun SaveToggle(
    isSaved: Boolean,
    isSaving: Boolean,
    contentDescription: String?,
    onClick: () -> Unit,
) {
    val container = if (isSaved) {
        MaterialTheme.colorScheme.primary.copy(alpha = 0.13f)
    } else {
        MaterialTheme.colorScheme.surfaceVariant
    }
    Box(
        modifier = Modifier
            .size(42.dp)
            .clip(RoundedCornerShape(13.dp))
            .background(container)
            .clickable(enabled = !isSaving) { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        if (isSaving) {
            CircularProgressIndicator(
                modifier = Modifier.size(18.dp),
                color = MaterialTheme.colorScheme.primary,
                strokeWidth = 2.dp,
            )
        } else {
            Icon(
                imageVector = if (isSaved) KiraIcons.Bookmark else KiraIcons.BookmarkOutline,
                contentDescription = contentDescription,
                tint = if (isSaved) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                modifier = Modifier.size(20.dp),
            )
        }
    }
}
