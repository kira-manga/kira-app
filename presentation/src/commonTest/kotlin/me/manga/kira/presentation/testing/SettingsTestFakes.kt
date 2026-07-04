package me.manga.kira.presentation.testing

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import me.manga.kira.domain.model.settings.CbzConversionProgress
import me.manga.kira.domain.model.settings.SettingsSnapshot
import me.manga.kira.domain.model.settings.SettingsToggle
import me.manga.kira.domain.repository.SettingsRepository

/**
 * Minimal in-memory [SettingsRepository] fake for ViewModel tests.
 *
 * Only [observeSettings] carries behaviour ([downloadedOnly] is flippable to drive the Library's
 * global "Downloaded only" override); the mutators are no-ops returning success.
 */
class FakeSettingsRepository : SettingsRepository {
    /** Flip this in a test to exercise the global "Downloaded only" library override. */
    val downloadedOnly = MutableStateFlow(false)

    /**
     * Hot CBZ-conversion progress flow (mirrors the real `single`-scoped MutableStateFlow). Push a
     * terminal snapshot here to drive the dialog into a terminal state for #14 dismiss tests.
     */
    val conversionProgress = MutableStateFlow(CbzConversionProgress())

    /** #14 — number of [clearConversionProgress] invocations, for asserting the dismiss path clears. */
    var clearConversionCalls = 0
        private set

    override fun observeSettings(): Flow<SettingsSnapshot> =
        downloadedOnly.map { dl ->
            SettingsSnapshot(
                downloadedOnly = dl,
                incognito = false,
                followSystemTheme = true,
                darkMode = false,
                pureBlack = false,
                cacheSizeBytes = 0L,
                useCbzFormat = true,
                autoConvertToCbz = false,
            )
        }

    override fun observeIncognito(): Flow<Boolean> = MutableStateFlow(false)

    override suspend fun setToggle(toggle: SettingsToggle, value: Boolean): Result<Unit> = Result.success(Unit)

    override suspend fun clearLargeCache(): Result<Unit> = Result.success(Unit)

    override suspend fun compressExistingDownloads(): Result<Unit> = Result.success(Unit)

    override fun observeCbzConversion(): Flow<CbzConversionProgress> = conversionProgress

    override fun stopConversion() = Unit

    override fun clearConversionProgress() {
        clearConversionCalls++
        conversionProgress.value = CbzConversionProgress()
    }
}
