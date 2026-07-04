package me.manga.yamiapk.presentation.common.componants

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState

fun LazyListState.isScrolledToTheEnd(): Boolean {
    val totalItems = layoutInfo.totalItemsCount
    if (totalItems == 0) return false
    return layoutInfo.visibleItemsInfo.lastOrNull()?.index == totalItems - 1
}

fun LazyGridState.isScrolledToTheEnd(): Boolean {
    val totalItems = layoutInfo.totalItemsCount
    if (totalItems == 0) return false
    return layoutInfo.visibleItemsInfo.lastOrNull()?.index == totalItems - 1
}