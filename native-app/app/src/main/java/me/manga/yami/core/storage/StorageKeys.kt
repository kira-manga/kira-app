package me.manga.yamiapk.core.storage
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey

object StorageKeys {
    const val DEFAULT_STORE_NAME  = "AppPrefs"


    val NEW_SOURCES = booleanPreferencesKey("new_sources_added")

    // Define boolean preference keys; requires import above
    val DownloadedOnly = booleanPreferencesKey("downloaded_only")
    val Incognito     = booleanPreferencesKey("incognito_mode")
    val READING_MODE = stringPreferencesKey("reading_mode")
    const val KEY_THEME_MODE  = "ThemeMode"
    const val KEY_THEME_SYSTEM  = "ThemeSystem"

    const val KEY_PURE_BLACK  = "PureBlack"

    val HAS_SHOWN_ADD_LIBRARY_PROMPT = booleanPreferencesKey("has_shown_add_library_prompt")
    val SELECTED_LANGUAGE = stringPreferencesKey("selected_language")


    const val KEY_USE_CBZ_FORMAT = "use_cbz_format"
    const val KEY_AUTO_CONVERT_TO_CBZ = "auto_convert_to_cbz"



}