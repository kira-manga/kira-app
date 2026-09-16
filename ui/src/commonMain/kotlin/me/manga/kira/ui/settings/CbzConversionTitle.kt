package me.manga.kira.ui.settings

import me.manga.kira.domain.model.settings.CbzConversionProgress
import me.manga.kira.ui.generated.resources.Res
import me.manga.kira.ui.generated.resources.cbz_conversion_partial
import me.manga.kira.ui.generated.resources.conversion_complete_
import me.manga.kira.ui.generated.resources.conversion_failed
import me.manga.kira.ui.generated.resources.conversion_stopped
import me.manga.kira.ui.generated.resources.converting_to_cbz
import org.jetbrains.compose.resources.StringResource

/** The dialog title resource; a completion marker alone is not a success verdict. */
internal fun cbzConversionTitle(progress: CbzConversionProgress): StringResource =
    when {
        progress.error != null -> Res.string.conversion_failed
        progress.successMessage != null ->
            when {
                progress.wasStopped -> Res.string.conversion_stopped
                progress.failedChapters > 0 && progress.convertedChapters == 0 -> Res.string.conversion_failed
                progress.failedChapters > 0 -> Res.string.cbz_conversion_partial
                else -> Res.string.conversion_complete_
            }
        else -> Res.string.converting_to_cbz
    }
