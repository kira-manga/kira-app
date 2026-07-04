package me.manga.yamiapk.navigation.routes

import android.annotation.SuppressLint
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavController
import me.manga.yamiapk.R
import me.manga.yamiapk.navigation.safePopBackStack
import me.manga.yamiapk.presentation.features.language.data.LanguageOption
import me.manga.yamiapk.presentation.features.language.ui.screens.LanguageSelectionScreen
import java.util.Locale

@SuppressLint("LocalContextConfigurationRead")
@Composable
fun LanguageScreenRoute(
    navController: NavController,
    backStackEntry: NavBackStackEntry,
){


    val context = LocalContext.current
    val supportedTags = context.resources.getStringArray(R.array.supported_languages)

    val availableLanguages = remember {
        supportedTags.map { tag ->
            val locale = Locale.forLanguageTag(tag)
            LanguageOption(tag, locale.getDisplayLanguage(locale))
        }
    }


    LanguageSelectionScreen(
        availableLanguages
    ){
        navController.safePopBackStack()
    }
}