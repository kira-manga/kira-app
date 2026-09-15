package me.manga.kira.locale

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection

/** BCP-47 language subtags whose app layout is right-to-left. */
private val RTL_LANGUAGE_SUBTAGS =
    setOf("ar", "fa", "he", "iw", "ur", "ps", "sd", "ug", "yi", "dv")

internal fun isRtlLanguageTag(tag: String): Boolean =
    tag
        .trim()
        .substringBefore('-')
        .substringBefore('_')
        .lowercase() in RTL_LANGUAGE_SUBTAGS

/** Existing non-Android direction policy; Android derives direction from its resolved Configuration. */
@Composable
internal fun selectedLanguageLayoutDirection(value: String?): LayoutDirection =
    when {
        value.isNullOrBlank() || !LocalAppLocale.isLiveLocaleSwitchSupported -> LocalLayoutDirection.current
        isRtlLanguageTag(value) -> LayoutDirection.Rtl
        else -> LayoutDirection.Ltr
    }
