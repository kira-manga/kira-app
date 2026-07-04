package me.manga.yamiapk.presentation.common.componants

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp


@Composable
fun ItemsGroup(color : Color = MaterialTheme.colorScheme.surfaceContainerHigh, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                color = color,
                shape = RoundedCornerShape(16.dp)
            )// Rounded card
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        content()
    }
}