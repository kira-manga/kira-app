package me.manga.kira.locale

/** BCP-47 language subtags whose app layout is right-to-left. */
private val RTL_LANGUAGE_SUBTAGS =
    setOf("ar", "fa", "he", "iw", "ur", "ps", "sd", "ug", "yi", "dv")

internal fun isRtlLanguageTag(tag: String): Boolean =
    tag
        .trim()
        .substringBefore('-')
        .substringBefore('_')
        .lowercase() in RTL_LANGUAGE_SUBTAGS
