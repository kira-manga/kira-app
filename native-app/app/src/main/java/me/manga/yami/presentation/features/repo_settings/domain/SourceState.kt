package me.manga.yamiapk.presentation.features.repo_settings.domain

import kotlinx.serialization.Serializable

@Serializable
enum class SourceState {
    WORKING,
    UNDER_MAINTENANCE,
    STOPPED,
    ADULT_18_PLUS
}