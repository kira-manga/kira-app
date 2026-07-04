package me.manga.yamiapk.presentation.common.componants.sources

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import me.manga.yamiapk.R
import me.manga.yamiapk.presentation.features.repo_settings.ui.components.RepoToggleItem
import me.manga.yamiapk.sources_repositry.BaseMangaRepository

@Composable
fun LanguageToggleWithAnimation(
    repos: List<BaseMangaRepository>,
    enabledStates: Map<String, Boolean>,
    onToggleLanguage: (String, Boolean) -> Unit,
) {
    val allEnabled = repos.any { enabledStates[it.API] == true }

    // Use AnimatedVisibility with proper enter and exit transitions
    AnimatedVisibility(
        visible = allEnabled,
        enter = fadeIn() + expandVertically(),  // Fade in and expand vertically
        exit = fadeOut() + shrinkVertically()   // Fade out and collapse vertically
    ) {
        // Show the repositories only when allEnabled is true
        Column {
            repos.forEach { repo ->
                val isEnabled = enabledStates[repo.API] ?: false
                RepoToggleItem(
                    title = repo.API,
                    description = stringResource(
                        if (isEnabled) R.string.enabled else R.string.disabled
                    ),
                    icon = ImageVector.vectorResource(repo.ICON),
                    checked = isEnabled,
                    onCheckedChange = { bol ->onToggleLanguage(repo.API,bol) }

                )
            }
        }
    }
}