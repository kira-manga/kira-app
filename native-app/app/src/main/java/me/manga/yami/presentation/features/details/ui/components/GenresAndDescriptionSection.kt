package me.manga.yamiapk.presentation.features.details.ui.components

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun GenresAndDescriptionSection(
    genres: List<String>,
    description: String,
    collapsedMaxGenres: Int = 4,
    collapsedMaxLines: Int = 4
) {
    var expanded by remember { mutableStateOf(false) }
    val visibleGenres = if (expanded || genres.size <= collapsedMaxGenres) genres else genres.take(collapsedMaxGenres)

    Column(Modifier.padding(0.dp)) {
        if (!expanded) {
            CollapsedDescription(description, collapsedMaxLines) { expanded = true }
        } else {
            ExpandedDescription(description) { expanded = false }
        }

        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterHorizontally),
            verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically)
        ) {
            visibleGenres.forEach { genre -> GenreChip(genre) }
            if (!expanded && genres.size > collapsedMaxGenres) MoreGenresChip(genres.size - collapsedMaxGenres) { expanded = true }
        }
    }
}

@Composable
private fun ExpandedDescription(text: String, onCollapse: () -> Unit) {
    Column(Modifier
        .fillMaxWidth()
        .animateContentSize()) {
        Text(text = text, textAlign = TextAlign.Center ,style = MaterialTheme.typography.bodyMedium, overflow = TextOverflow.Ellipsis, modifier = Modifier.align(Alignment.CenterHorizontally))

        IconButton(onCollapse, Modifier.align(Alignment.CenterHorizontally)) { Icon(Icons.Default.ExpandLess, contentDescription = null) }
    }
}

@Composable
private fun MoreGenresChip(remaining: Int, onClick: () -> Unit) {
    Box(
        Modifier
            .border(
                1.dp,
                MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f),
                RoundedCornerShape(4.dp)
            )
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp, horizontal = 12.dp),
        contentAlignment = Alignment.Center
    ) {
        Text("+$remaining more",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
        )
    }
}



@Composable
fun GenreChip(text: String) {
    Box(
        Modifier
            .border(
                1.dp,
                MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f),
                RoundedCornerShape(6.dp)
            )
            .padding(vertical = 8.dp, horizontal = 12.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text,
            maxLines = 1,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
        )
    }
}


@Composable
fun CollapsedDescription(text: String, maxLines: Int, onExpand: () -> Unit) {
    Box(
        Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
    ) {
        Text(text = text, textAlign = TextAlign.Center,style = MaterialTheme.typography.bodyMedium, maxLines = maxLines, overflow = TextOverflow.Ellipsis, modifier = Modifier.align(Alignment.Center))
        Box(
            modifier = Modifier
                .fillMaxSize()         // height of the fade
                .align(Alignment.BottomCenter)
                .background(
                    brush = Brush.verticalGradient(
                        colors = listOf(
                            Color.Transparent,
                            MaterialTheme.colorScheme.background,

                            )
                    )
                )
        )
        IconButton(onExpand, Modifier
            .align(Alignment.BottomCenter)
            .size(24.dp)) { Icon(Icons.Default.ExpandMore, contentDescription = null) }
    }
}
