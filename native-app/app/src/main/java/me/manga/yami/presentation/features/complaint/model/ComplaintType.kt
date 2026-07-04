package me.manga.yamiapk.presentation.features.complaint.model

import android.content.Context
import me.manga.yamiapk.R

enum class ComplaintType { TECHNICAL,LANGUAGES, SITES_ADD,SITE_ERROR, FEATURES, CUSTOM;

    fun getDisplayName(context: Context): String {
        return when (this) {
            TECHNICAL       -> context.getString(R.string.error_in_the_app)
            LANGUAGES       -> context.getString(R.string.add_languages)
            SITES_ADD       -> context.getString(R.string.add_manga_site)
            SITE_ERROR      -> context.getString(R.string.error_in_manga_site)
            FEATURES        -> context.getString(R.string.ask_to_add_features)
            CUSTOM          -> context.getString(R.string.custom_feedback)

        }
    }

}
