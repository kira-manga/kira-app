package me.manga.kira.platform.locale

import android.content.Context
import com.russhwolf.settings.ObservableSettings
import com.russhwolf.settings.SharedPreferencesSettings
import me.manga.kira.platform.storage.DataStoreHelper

/** Application-owned locale state, available before Koin or an Activity is initialized. */
interface AndroidLocaleOwner {
    val androidLocaleState: AndroidLocaleState
}

/**
 * Bootstraps the existing settings cell from the unwrapped Application base during attach.
 * DI reuses these exact instances; resource getters never initialize or resolve Koin.
 */
class AndroidLocaleState(rawApplicationBase: Context) {
    val settings: ObservableSettings =
        SharedPreferencesSettings(
            rawApplicationBase.getSharedPreferences(SETTINGS_NAME, Context.MODE_PRIVATE),
        )
    val dataStore: DataStoreHelper = DataStoreHelper(settings)
    val resourceLocales: AndroidResourceLocales = AndroidResourceLocales(rawApplicationBase, dataStore)

    private companion object {
        const val SETTINGS_NAME = "kira_settings"
    }
}

/** Returns the real Application's already-attached settings owner without consulting Koin. */
fun Context.androidLocaleState(): AndroidLocaleState =
    (applicationContext as AndroidLocaleOwner).androidLocaleState

/** Takes a fresh application-resource snapshot for one notification build, not its lifetime. */
fun Context.localizedResourceSnapshot(): Context = androidLocaleState().resourceLocales.snapshot()
