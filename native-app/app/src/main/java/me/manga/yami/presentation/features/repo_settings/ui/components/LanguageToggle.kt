package me.manga.yamiapk.presentation.features.repo_settings.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import me.manga.yamiapk.R
import me.manga.yamiapk.presentation.common.componants.list_items.SwitchItem
import me.manga.yamiapk.sources_repositry.BaseMangaRepository

@Composable
fun LanguageToggle(
    language: String,
    repos: List<BaseMangaRepository>,
    enabledStates: Map<String, Boolean>,
    onToggleLanguage: (Boolean) -> Unit,
    icon: ImageVector? = null,
    description: String? = null
) {
    // “all on” if every repo under this language is on
    val allEnabled = repos.any { enabledStates[it.API] == true }

    SwitchItem(
        title = language,
        description = description ?: stringResource(
            R.string.enable_disable_all_sources,
            language
        ),
        icon = icon,
        checked = allEnabled,
        onCheckedChange = onToggleLanguage
    )
}