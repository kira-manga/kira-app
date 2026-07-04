package me.manga.yamiapk.presentation.features.home.data

sealed class SearchType {
    data class Normal(val query :String) : SearchType()
    data class SORT(val query: String, val sortType: String,val genres: String) : SearchType()
    data class GENRES(val query :String,val genres: String  ) : SearchType()
    fun toNormalQuery():String = if (this is Normal) query else ""

}