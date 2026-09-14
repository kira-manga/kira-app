package me.manga.kira.ui.library

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import me.manga.kira.ui.components.KiraIcons
import me.manga.kira.ui.generated.resources.Res
import me.manga.kira.ui.generated.resources.library_empty_desc_format
import me.manga.kira.ui.generated.resources.library_empty_message_format
import me.manga.kira.ui.generated.resources.library_no_matching_items
import me.manga.kira.ui.generated.resources.no_results_found
import me.manga.kira.ui.generated.resources.title_library
import me.manga.kira.ui.theme.LocalSpacing
import org.jetbrains.compose.resources.stringResource

/** Empty-only scrolling forwards drag gestures to the enclosing pull-to-refresh container. */
@Composable
internal fun libraryEmptyContent(
    hasLibraryItems: Boolean,
    isSearching: Boolean,
    modifier: Modifier = Modifier,
) {
    val spacing = LocalSpacing.current
    Box(
        modifier =
            modifier
                // Fill before scrolling so short content remains centered in the viewport.
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(spacing.xxl),
        contentAlignment = Alignment.Center,
    ) {
        if (hasLibraryItems) {
            libraryNoMatchesMessage(isSearching)
        } else {
            emptyLibraryMessage()
        }
    }
}

@Composable
private fun libraryNoMatchesMessage(isSearching: Boolean) {
    Text(
        text =
            stringResource(
                if (isSearching) Res.string.no_results_found else Res.string.library_no_matching_items,
            ),
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
    )
}

@Composable
private fun emptyLibraryMessage() {
    val spacing = LocalSpacing.current
    val libraryName = stringResource(Res.string.title_library)
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(
            imageVector = KiraIcons.Empty,
            contentDescription = stringResource(Res.string.library_empty_desc_format, libraryName),
            modifier = Modifier.size(spacing.xxl + spacing.xxl + spacing.sm),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.size(spacing.lg))
        Text(
            text = stringResource(Res.string.library_empty_message_format, libraryName),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onBackground,
        )
    }
}
