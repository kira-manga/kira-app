package me.manga.yamiapk.ad_mob.util

/**
 * Sealed class representing either a content item or an ad slot in a list.
 */
sealed class ListEntryWithAd<out T> {
    data class Item<out T>(val data: T) : ListEntryWithAd<T>()
    data class Ad(val adId: Int) : ListEntryWithAd<Nothing>()
}

/**
 * Extension to check if entry is an ad.
 */
val <T> ListEntryWithAd<T>.isAd: Boolean
    get() = this is ListEntryWithAd.Ad

/**
 * Extension to check if entry is a content item.
 */
val <T> ListEntryWithAd<T>.isItem: Boolean
    get() = this is ListEntryWithAd.Item

/**
 * Extension to safely get item data or null.
 */
fun <T> ListEntryWithAd<T>.getItemOrNull(): T? =
    (this as? ListEntryWithAd.Item)?.data

/**
 * Extension to safely get ad ID or null.
 */
fun <T> ListEntryWithAd<T>.getAdIdOrNull(): Int? =
    (this as? ListEntryWithAd.Ad)?.adId

/**
 * Extension to fold over the sealed class.
 */
inline fun <T, R> ListEntryWithAd<T>.fold(
    onItem: (T) -> R,
    onAd: (Int) -> R
): R = when (this) {
    is ListEntryWithAd.Item -> onItem(data)
    is ListEntryWithAd.Ad -> onAd(adId)
}