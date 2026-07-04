package me.manga.yamiapk.domain.auth


interface UserIdProvider {
    /**
     * Returns a stable, app‐unique user ID.
     */
    fun getUserId(): String
}