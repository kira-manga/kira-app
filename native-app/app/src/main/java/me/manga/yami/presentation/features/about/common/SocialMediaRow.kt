package me.manga.yamiapk.presentation.features.about.common

import android.annotation.SuppressLint
import android.content.Context
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Facebook
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import me.manga.yamiapk.R
import me.manga.yamiapk.presentation.features.about.common.icons.CustomIcons
import me.manga.yamiapk.presentation.features.about.common.icons.Discord
import me.manga.yamiapk.presentation.features.about.common.icons.X

/**
 * Enhanced social media row with adaptive icon sizing and improved visual design.
 */
@SuppressLint("UnusedBoxWithConstraintsScope")
@Composable
fun SocialMediaRow(
    modifier: Modifier = Modifier,
    onTwitter: (() -> Unit)? = null,
    onFacebook: (() -> Unit)? = null,
    onInstagram: (() -> Unit)? = null,
    onWhatsApp: (() -> Unit)? = null,
    onDiscord: (() -> Unit)? = null,
    onWebsite: (() -> Unit)? = null,
) {
    val context: Context = LocalContext.current

    // Adaptive sizing based on available width
    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        val buttonCount = 6
        val spacing = 8.dp
        val totalSpacing = spacing * (buttonCount - 1)
        val availableWidth = maxWidth - totalSpacing
        val buttonSize = (availableWidth / buttonCount).coerceIn(36.dp, 56.dp)
        val iconSize = (buttonSize * 0.5f).coerceIn(18.dp, 28.dp)

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            SocialMediaButton(
                icon = CustomIcons.X,
                contentDescription = "Twitter",
                buttonSize = buttonSize,
                iconSize = iconSize,
                onClick = onTwitter ?: { openTwitter(context, "yami_manga_me") }
            )

            SocialMediaButton(
                icon = Icons.Default.Facebook,
                contentDescription = "Facebook",
                buttonSize = buttonSize,
                iconSize = iconSize,
                onClick = onFacebook ?: { openFacebook(context, "61577403584218") }
            )

            SocialMediaButton(
                icon = ImageVector.vectorResource(R.drawable.ic_instagram),
                contentDescription = "Instagram",
                buttonSize = buttonSize,
                iconSize = iconSize,
                onClick = onInstagram ?: { openInstagram(context, "yami_manga_me") }
            )

            SocialMediaButton(
                icon = ImageVector.vectorResource(R.drawable.ic_whatsapp),
                contentDescription = "WhatsApp",
                buttonSize = buttonSize,
                iconSize = iconSize,
                onClick = onWhatsApp ?: { sendWhatsAppMessage(context, "01558657735", "Hey from Yami!") }
            )

            SocialMediaButton(
                icon = CustomIcons.Discord,
                contentDescription = "Discord",
                buttonSize = buttonSize,
                iconSize = iconSize,
                onClick = onDiscord ?: { /* default: no-op */ }
            )

            SocialMediaButton(
                icon = ImageVector.vectorResource(R.drawable.earth_svgrepo_com),
                contentDescription = "Website",
                buttonSize = buttonSize,
                iconSize = iconSize,
                onClick = onWebsite ?: { openBrowser(context, "https://yamimanga.me/") }
            )
        }
    }
}

@Composable
private fun SocialMediaButton(
    icon: ImageVector,
    contentDescription: String,
    buttonSize: Dp,
    iconSize: Dp,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    var isPressed by remember { mutableStateOf(false) }

    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.85f else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessHigh
        ),
        label = "social_button_scale"
    )

    IconButton(
        onClick = {
            isPressed = true
            onClick()
        },
        modifier = modifier
            .size(buttonSize)
            .scale(scale),
        colors = IconButtonDefaults.iconButtonColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f),
            contentColor = MaterialTheme.colorScheme.primary
        )
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.2f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                modifier = Modifier.size(iconSize),
                tint = MaterialTheme.colorScheme.primary
            )
        }
    }

    LaunchedEffect(isPressed) {
        if (isPressed) {
            kotlinx.coroutines.delay(150)
            isPressed = false
        }
    }
}