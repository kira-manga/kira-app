package me.manga.kira.ui.settings.feedback

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import me.manga.kira.presentation.settings.feedback.SettingsFeedbackEntry
import me.manga.kira.ui.components.KiraSocialMediaRow
import me.manga.kira.ui.generated.resources.Res
import me.manga.kira.ui.generated.resources.connect_with_us_in_social_media
import me.manga.kira.ui.generated.resources.enter_the_site_url
import me.manga.kira.ui.generated.resources.enter_your_language
import me.manga.kira.ui.generated.resources.request_add_language
import me.manga.kira.ui.generated.resources.request_adding_source_full
import me.manga.kira.ui.generated.resources.request_feature_bug
import me.manga.kira.ui.generated.resources.request_feedback_clean_start
import me.manga.kira.ui.generated.resources.request_language_prompt
import me.manga.kira.ui.generated.resources.we_d_love_to_hear_from_you
import me.manga.kira.ui.generated.resources.we_will_add_it_as_soon_it_possible
import me.manga.kira.ui.generated.resources.you_ll_receive_a_prompt_response
import me.manga.kira.ui.generated.resources.your_feedback
import me.manga.kira.ui.theme.LocalSpacing
import org.jetbrains.compose.resources.stringResource

/** Fixed requests share the existing report form, live handles and recovery controls. */
@Suppress("ktlint:standard:function-naming", "FunctionNaming")
@Composable
internal fun SettingsFeedbackTitle(entry: SettingsFeedbackEntry) {
    Column(verticalArrangement = Arrangement.spacedBy(LocalSpacing.current.sm)) {
        Text(
            when (entry) {
                SettingsFeedbackEntry.General -> stringResource(Res.string.request_feature_bug)
                is SettingsFeedbackEntry.SourceRequest -> stringResource(Res.string.request_adding_source_full)
                is SettingsFeedbackEntry.LanguageRequest -> stringResource(Res.string.request_add_language)
            },
        )
        if (entry != SettingsFeedbackEntry.General) {
            Text(
                stringResource(Res.string.we_d_love_to_hear_from_you),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Suppress("ktlint:standard:function-naming", "FunctionNaming")
@Composable
internal fun SettingsFeedbackRequestIntroduction(entry: SettingsFeedbackEntry) {
    when (entry) {
        SettingsFeedbackEntry.General -> Unit
        is SettingsFeedbackEntry.SourceRequest -> Text(stringResource(Res.string.we_will_add_it_as_soon_it_possible))
        is SettingsFeedbackEntry.LanguageRequest -> Text(stringResource(Res.string.request_language_prompt))
    }
    Text(stringResource(Res.string.request_feedback_clean_start), style = MaterialTheme.typography.bodySmall)
}

@Composable
internal fun settingsFeedbackBodyLabel(entry: SettingsFeedbackEntry): String =
    when (entry) {
        SettingsFeedbackEntry.General -> stringResource(Res.string.your_feedback)
        is SettingsFeedbackEntry.SourceRequest -> stringResource(Res.string.enter_the_site_url)
        is SettingsFeedbackEntry.LanguageRequest -> stringResource(Res.string.enter_your_language)
    }

@Suppress("ktlint:standard:function-naming", "FunctionNaming")
@Composable
internal fun SettingsFeedbackSocialFooter(onOpenUrl: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(LocalSpacing.current.sm)) {
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Text(
            stringResource(Res.string.connect_with_us_in_social_media),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            stringResource(Res.string.you_ll_receive_a_prompt_response),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        KiraSocialMediaRow(onOpenUrl = onOpenUrl)
    }
}
