package me.manga.yamiapk.core.network_connectivity

import kotlinx.coroutines.flow.Flow

interface ConnectivityObserver {

    fun observe():  Flow<Status>

    enum class Status {
        Available, Unavailable, Losing, Lost
    }
}