package me.manga.kira.presentation.whatsnew

import androidx.lifecycle.ViewModelStore
import me.manga.kira.core.result.AppResult
import me.manga.kira.domain.model.whatsnew.MediaType
import me.manga.kira.domain.model.whatsnew.WhatsNewFeature
import me.manga.kira.domain.repository.WhatsNewRepository
import me.manga.kira.domain.usecase.whatsnew.GetWhatsNewFeaturesUseCase
import me.manga.kira.domain.usecase.whatsnew.MarkWhatsNewSeenUseCase

internal class WhatsNewViewModelTestFixture {
    val store = ViewModelStore()
    private var nextViewModelId = 0

    fun viewModel(repo: FakeWhatsNewRepository) =
        WhatsNewViewModel(
            GetWhatsNewFeaturesUseCase(repo),
            MarkWhatsNewSeenUseCase(repo),
        ).also { store.put("whats-new-${nextViewModelId++}", it) }
}

internal fun feature(title: String) =
    WhatsNewFeature(
        title = title,
        description = "desc",
        mediaType = MediaType.IMAGE,
    )

internal class FakeWhatsNewRepository(
    var behavior: suspend () -> AppResult<List<WhatsNewFeature>> = { AppResult.Success(emptyList()) },
) : WhatsNewRepository {
    var loadCalls = 0
    var markSeenCalls = 0

    override suspend fun getFeatures(): AppResult<List<WhatsNewFeature>> {
        loadCalls++
        return behavior()
    }

    override suspend fun markSeen() {
        markSeenCalls++
    }
}
