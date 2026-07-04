package me.manga.yamiapk.presentation.common.componants.app_bars

import androidx.compose.foundation.layout.RowScope
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TopAppBarCom(
    title: String,
    backgroundColor: Color = MaterialTheme.colorScheme.background,
    textColor: Color = MaterialTheme.colorScheme.onBackground,
    titleSize : TextUnit = 24.sp,
    fontWeight: FontWeight? = FontWeight.Bold,
    navigationIcon: (@Composable () -> Unit) = {},
    actions: @Composable RowScope.() -> Unit = {}
) {

    TopAppBar(
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = backgroundColor
        ),
        title = {
            Text(
                text = title,
                maxLines = 1,
                color = textColor,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.titleLarge.copy(fontSize = titleSize),
                fontWeight = fontWeight
            )
        },
        navigationIcon = navigationIcon,
        actions = actions
    )
}