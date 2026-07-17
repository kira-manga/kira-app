package me.manga.kira.ui.sourceaccess

/** Navigation and URL callbacks owned by the platform route adapter. */
data class StartReadingActions(
    val onActivationSucceeded: () -> Unit,
    val onImport: () -> Unit,
    val onContinueToLibrary: () -> Unit,
    val onOpenUrl: (String) -> Unit,
    val onBack: (() -> Unit)? = null,
)
