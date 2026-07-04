    package me.manga.yamiapk.presentation.features.complaint.model

    import androidx.annotation.StringRes
    import android.content.Context
    import me.manga.yamiapk.R

    enum class ComplaintStatus(@StringRes private val labelRes: Int) {
        OPEN(R.string.status_open),
        IN_PROGRESS(R.string.status_in_progress),
        RESOLVED(R.string.status_resolved),
        CLOSED(R.string.status_closed),
        PLANNED(R.string.status_planned),
        PINNED(R.string.status_pinned),
        UNKNOWN(R.string.status_unknown),
        NOT_PLANNED(R.string.status_not_planned);

        /**
         * Returns the localized display name based on the current locale.
         */
        fun getDisplayName(context: Context): String =
            context.getString(labelRes)
    }
