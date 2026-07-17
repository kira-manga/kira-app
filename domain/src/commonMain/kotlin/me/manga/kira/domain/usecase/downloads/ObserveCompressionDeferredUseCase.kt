package me.manga.kira.domain.usecase.downloads

import kotlinx.coroutines.flow.Flow
import me.manga.kira.domain.repository.CompressionDeferralRepository

/**
 * Observe whether downloaded-chapter compression is currently deferred by iOS Low Power Mode (the
 * user opted out of compressing during Low Power Mode). The Details screen renders a clear
 * "Paused — Low Power Mode" state for finished-but-not-archived chapters instead of an endless
 * "Finalizing…". Always emits `false` on Android/Desktop.
 *
 * Contract §6 SRP: ONE rule — delegate to [CompressionDeferralRepository.observeLowPowerDeferral].
 * DIP: `:presentation` depends on this use case, never on the repository impl. Bound as `factory`
 * (stateless, cheap).
 */
class ObserveCompressionDeferredUseCase(
    private val repository: CompressionDeferralRepository,
) {
    operator fun invoke(): Flow<Boolean> = repository.observeLowPowerDeferral()
}
