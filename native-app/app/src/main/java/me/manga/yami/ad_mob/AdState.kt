package me.manga.yamiapk.ad_mob

/**
 * Sealed class representing the state of any ad type.
 */
sealed class AdState {
    data object Idle : AdState()
    data object Loading : AdState()
    data object Ready : AdState()
    data object Showing : AdState()
    data class Error(val message: String, val code: Int? = null) : AdState()

    val isIdle: Boolean get() = this is Idle
    val isLoading: Boolean get() = this is Loading
    val isReady: Boolean get() = this is Ready
    val isShowing: Boolean get() = this is Showing
    val isError: Boolean get() = this is Error

    val canLoad: Boolean get() = this is Idle || this is Error
    val canShow: Boolean get() = this is Ready
}

/**
 * Result of showing an ad.
 */
sealed class AdShowResult {
    data class RewardEarned(val type: String, val amount: Int) : AdShowResult()
    data object Dismissed : AdShowResult()
    data class Failed(val error: String) : AdShowResult()
}

/**
 * Result of loading an ad.
 */
sealed class AdLoadResult {
    data object Success : AdLoadResult()
    data class Failed(val error: String, val code: Int? = null) : AdLoadResult()
}