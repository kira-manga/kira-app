package me.manga.kira.ui.settings

import me.manga.kira.domain.model.settings.CbzConversionProgress
import me.manga.kira.ui.generated.resources.Res
import me.manga.kira.ui.generated.resources.cbz_conversion_partial
import me.manga.kira.ui.generated.resources.conversion_complete_
import me.manga.kira.ui.generated.resources.conversion_failed
import me.manga.kira.ui.generated.resources.conversion_stopped
import me.manga.kira.ui.generated.resources.converting_to_cbz
import kotlin.test.Test
import kotlin.test.assertEquals

class CbzConversionTitleTest {
    @Test
    fun completedRunsDistinguishPartialAndTotalFailureFromCompleteSuccess() {
        val completed =
            CbzConversionProgress(
                totalChapters = 2,
                convertedChapters = 2,
                successMessage = "completed",
            )
        assertEquals(Res.string.conversion_complete_, cbzConversionTitle(completed))
        assertEquals(
            Res.string.cbz_conversion_partial,
            cbzConversionTitle(completed.copy(convertedChapters = 1, failedChapters = 1)),
        )
        assertEquals(
            Res.string.conversion_failed,
            cbzConversionTitle(completed.copy(convertedChapters = 0, failedChapters = 2)),
        )
    }

    @Test
    fun stoppedErrorAndActiveRunsKeepTheirTitlePrecedence() {
        val stopped =
            CbzConversionProgress(
                totalChapters = 2,
                failedChapters = 1,
                successMessage = "completed",
                wasStopped = true,
            )
        assertEquals(Res.string.conversion_stopped, cbzConversionTitle(stopped))
        assertEquals(Res.string.conversion_failed, cbzConversionTitle(stopped.copy(error = "failed")))
        assertEquals(
            Res.string.converting_to_cbz,
            cbzConversionTitle(stopped.copy(isConverting = true, successMessage = null, wasStopped = false)),
        )
    }
}
