package me.manga.kira.ui.components

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import me.manga.kira.ui.about.Discord
import me.manga.kira.ui.about.Facebook
import me.manga.kira.ui.about.Instagram
import me.manga.kira.ui.about.Public
import me.manga.kira.ui.about.SocialMediaIcons
import me.manga.kira.ui.about.WhatsApp
import me.manga.kira.ui.about.X

/**
 * Reusable adaptive 6-button brand-icon social row (NP Phase 2, GAP-SET-12).
 *
 * Hoisted into `:ui/.../components` from the About screen's private `SocialMediaRow` so BOTH the
 * About screen AND the Settings Feedback dialog (and the Language request dialog, native shows the
 * social section there too) can reuse one row. The icon glyphs come from the existing
 * [me.manga.kira.ui.about.SocialMediaIcons] vendored-glyph object (Task #296) — no new icon
 * assets, no `compose.materialIconsExtended` dep.
 *
 * **Callback-only `:ui`**: the row never opens a URL itself — each tappable button invokes
 * [onOpenUrl] with one of the brand URL constants ([TwitterUrl] / [FacebookUrl] / [InstagramUrl] /
 * [WhatsAppUrl] / [WebsiteUrl], all verbatim from the legacy `SocialMediaRow.kt`). The host route
 * adapter forwards the URL to the platform `IntentLauncher.openUrl`. The Discord button has no
 * landing page in the source (legacy `default: no-op`) and dispatches nothing.
 *
 * **Layout**: [BoxWithConstraints]-driven adaptive sizing — six buttons with 8.dp inter-spacing,
 * button size coerced into 36..56.dp and icon size into 18..28.dp — byte-identical to the legacy
 * `SocialMediaRow` math and to the About screen's private copy.
 *
 * **Press feedback**: each button shrinks to 85% and springs back (bouncy spring, high stiffness) on
 * tap, resetting after 150ms — a faithful port of the legacy `SocialMediaButton` press animation.
 */
@Composable
fun KiraSocialMediaRow(
    onOpenUrl: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
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
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SocialMediaButton(
                icon = SocialMediaIcons.X,
                contentDescription = "Twitter",
                buttonSize = buttonSize,
                iconSize = iconSize,
                onClick = { onOpenUrl(TwitterUrl) },
            )
            SocialMediaButton(
                icon = SocialMediaIcons.Facebook,
                contentDescription = "Facebook",
                buttonSize = buttonSize,
                iconSize = iconSize,
                onClick = { onOpenUrl(FacebookUrl) },
            )
            SocialMediaButton(
                icon = SocialMediaIcons.Instagram,
                contentDescription = "Instagram",
                buttonSize = buttonSize,
                iconSize = iconSize,
                onClick = { onOpenUrl(InstagramUrl) },
            )
            SocialMediaButton(
                icon = SocialMediaIcons.WhatsApp,
                contentDescription = "WhatsApp",
                buttonSize = buttonSize,
                iconSize = iconSize,
                onClick = { onOpenUrl(WhatsAppUrl) },
            )
            SocialMediaButton(
                icon = SocialMediaIcons.Discord,
                contentDescription = "Discord",
                buttonSize = buttonSize,
                iconSize = iconSize,
                onClick = { /* no Discord landing page in source — matches legacy no-op */ },
            )
            SocialMediaButton(
                icon = SocialMediaIcons.Public,
                contentDescription = "Website",
                buttonSize = buttonSize,
                iconSize = iconSize,
                onClick = { onOpenUrl(WebsiteUrl) },
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
) {
    // Tactile press feedback — byte-for-byte match of native SocialMediaButton.kt:118-163: tapping a
    // button shrinks it to 85% and springs back via a bouncy spring, then resets the pressed flag
    // after 150ms so the next tap re-triggers the animation.
    var isPressed by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.85f else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessHigh,
        ),
        label = "social_button_scale",
    )

    IconButton(
        onClick = {
            isPressed = true
            onClick()
        },
        modifier = Modifier
            .size(buttonSize)
            .scale(scale),
        colors = IconButtonDefaults.iconButtonColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f),
            contentColor = MaterialTheme.colorScheme.primary,
        ),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.2f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                modifier = Modifier.size(iconSize),
                tint = MaterialTheme.colorScheme.primary,
            )
        }
    }

    LaunchedEffect(isPressed) {
        if (isPressed) {
            delay(150)
            isPressed = false
        }
    }
}

/** Twitter/X URL — matches legacy `SocialMediaRow.kt:99` literal. */
const val TwitterUrl: String = "https://twitter.com/yami_manga_me"

/** Facebook URL — matches legacy `SocialMediaRow.kt:107` literal. */
const val FacebookUrl: String = "https://www.facebook.com/61577403584218"

/** Instagram URL — matches legacy `SocialMediaRow.kt:115` literal. */
const val InstagramUrl: String = "https://www.instagram.com/yami_manga_me"

/** WhatsApp URL — matches legacy `SocialMediaRow.kt:125-128` literal (Egypt country code). */
const val WhatsAppUrl: String =
    "https://api.whatsapp.com/send?phone=201558657735&text=Hey%20from%20Kira!"

/** Project website URL — matches legacy `SocialMediaRow.kt:145` literal. */
const val WebsiteUrl: String = "https://yamimanga.me/"
