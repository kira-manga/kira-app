package me.manga.kira.ui.complaint

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Update
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import me.manga.kira.ui.generated.resources.Res
import me.manga.kira.ui.generated.resources.np_closure_reason
import me.manga.kira.ui.generated.resources.np_closure_reason_done
import me.manga.kira.ui.generated.resources.np_closure_reason_done_wait_update
import me.manga.kira.ui.generated.resources.np_closure_reason_label
import me.manga.kira.ui.generated.resources.np_closure_reason_other
import me.manga.kira.ui.generated.resources.np_closure_reason_pinned
import org.jetbrains.compose.resources.stringResource

/**
 * `:ui`-side port of the native `ClosureReasonType` enum (GAP-CMP-02 / GAP-CMP-23).
 *
 * Mirrors `app/.../complaint/model/ClosureReasonType.kt` 1:1 — the [key] prefix and [fromString]
 * heuristic are byte-for-byte identical so the rework re-derives the same type from a stored
 * `"${key}: ${reason}"` reason string the admin closure dialog writes (and the legacy data the
 * `:data` mapper surfaces via [me.manga.kira.domain.model.complaint.ComplaintSummary.reason]).
 *
 * Lives in `:ui` (not `:domain`/`:presentation`) because it is purely a presentation concern:
 * the colour scheme + display text + icon are Compose/Material types, and the reason string
 * itself round-trips through `:domain` as a plain `String?`. No banned `Any`, no platform reach.
 */
enum class ClosureReasonType(val key: String) {
    DONE("done"),
    DONE_WAIT_UPDATE("done_and_wait_update"),
    PINNED("pinned"),
    OTHER("other"),
    ;

    companion object {
        /**
         * Heuristic parse mirroring the native `ClosureReasonType.fromString`. Matches the stored
         * reason text (which may carry a `"${key}: "` prefix) by substring, so a reason like
         * `"pinned : …"` resolves to [PINNED] and `"done_and_wait_update: …"` to [DONE_WAIT_UPDATE].
         */
        fun fromString(reason: String?): ClosureReasonType {
            if (reason.isNullOrBlank()) return OTHER
            val done = reason.contains("done", ignoreCase = true)
            val wait = reason.contains("wait", ignoreCase = true)
            val update = reason.contains("update", ignoreCase = true)
            return when {
                done && wait && update -> DONE_WAIT_UPDATE
                done -> DONE
                reason.contains("pinned", ignoreCase = true) -> PINNED
                else -> OTHER
            }
        }
    }
}

/**
 * Localized display label for a [ClosureReasonType] — mirrors the native `getDisplayText()`.
 */
@Composable
fun ClosureReasonType.displayText(): String = when (this) {
    ClosureReasonType.DONE -> stringResource(Res.string.np_closure_reason_done)
    ClosureReasonType.DONE_WAIT_UPDATE -> stringResource(Res.string.np_closure_reason_done_wait_update)
    ClosureReasonType.PINNED -> stringResource(Res.string.np_closure_reason_pinned)
    ClosureReasonType.OTHER -> stringResource(Res.string.np_closure_reason_other)
}

/**
 * Container + content colour pair for a [ClosureReasonType] — mirrors the native `getColorScheme()`
 * (green DONE / blue DONE_WAIT_UPDATE / white-on-black PINNED / errorContainer OTHER).
 */
@Composable
fun ClosureReasonType.colorScheme(): Pair<Color, Color> = when (this) {
    ClosureReasonType.DONE -> Color(0xFF4CAF50).copy(alpha = 0.2f) to Color(0xFF2E7D32)
    ClosureReasonType.DONE_WAIT_UPDATE -> Color(0xFF2196F3).copy(alpha = 0.2f) to Color(0xFF1565C0)
    ClosureReasonType.OTHER ->
        MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f) to
            MaterialTheme.colorScheme.onErrorContainer
    ClosureReasonType.PINNED -> Color(0xFFFFFFFF).copy(alpha = 0.8f) to Color(0xFF000000)
}

/**
 * Closure-reason card — port of the native `ComplaintComponents.kt` `ClosureReasonCard`
 * (GAP-CMP-02). Renders on user-side CLOSED / PINNED complaint cards (and the dialog preview)
 * when a [reason] is present in metadata.
 *
 * Layout matches native: a `Card` (r8, colour by [ClosureReasonType.colorScheme]) with a leading
 * icon (DONE→CheckCircle, DONE_WAIT_UPDATE→Update, PINNED→PushPin, OTHER→Info), the
 * `closure_reason_label` heading, an optional non-OTHER type chip, and the reason text (maxLines
 * 10). The type is re-derived from [reason] via [ClosureReasonType.fromString].
 */
@Composable
fun ClosureReasonCard(reason: String, modifier: Modifier = Modifier) {
    val type = ClosureReasonType.fromString(reason)
    val (containerColor, contentColor) = type.colorScheme()
    val icon: ImageVector = when (type) {
        ClosureReasonType.DONE -> Icons.Default.CheckCircle
        ClosureReasonType.DONE_WAIT_UPDATE -> Icons.Default.Update
        ClosureReasonType.PINNED -> Icons.Default.PushPin
        ClosureReasonType.OTHER -> Icons.Default.Info
    }

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = containerColor),
        shape = RoundedCornerShape(8.dp),
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = stringResource(Res.string.np_closure_reason),
                tint = contentColor,
                modifier = Modifier.size(16.dp),
            )
            Spacer(modifier = Modifier.width(8.dp))
            Column {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = stringResource(Res.string.np_closure_reason_label),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = contentColor,
                    )
                    if (type != ClosureReasonType.OTHER) {
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = contentColor.copy(alpha = 0.2f),
                        ) {
                            Text(
                                text = type.displayText(),
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                                style = MaterialTheme.typography.labelSmall,
                                color = contentColor,
                                fontWeight = FontWeight.Medium,
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = reason,
                    style = MaterialTheme.typography.bodySmall,
                    color = contentColor,
                    maxLines = 10,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
