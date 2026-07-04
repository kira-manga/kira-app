package me.manga.yamiapk.ad_mob.util.ads_lists

import me.manga.yamiapk.ad_mob.util.ListEntryWithAd

/**
 * Interleaves ads into a list of items at specific breakpoints.
 *
 * Default pattern: ad after item 4, 8, then every 8 items thereafter.
 *
 * @param items The list of content items
 * @param removeAds If true, returns items without ads
 * @return List with ads interleaved at breakpoints
 */
fun <T> interleaveAds(
    items: List<T>,
    removeAds: Boolean = false
): List<ListEntryWithAd<T>> {
    if (items.isEmpty()) return emptyList()
    if (removeAds) return items.map { ListEntryWithAd.Item(it) }

    return try {
        val breakpoints = buildBreakpoints(items.size)
        val result = ArrayList<ListEntryWithAd<T>>(items.size + breakpoints.size)
        var adIndex = 0

        items.forEachIndexed { idx, item ->
            result += ListEntryWithAd.Item(item)
            // idx+1 is the 1-based position; if it's in our breakpoints, insert an ad
            if ((idx + 1) in breakpoints) {
                result += ListEntryWithAd.Ad(adIndex++)
            }
        }

        result
    } catch (e: Exception) {
        items.map { ListEntryWithAd.Item(it) }
    }
}

/**
 * Interleaves ads with custom breakpoints.
 *
 * @param items The list of content items
 * @param initialBreakpoints Starting breakpoints (e.g., listOf(4, 8))
 * @param interval Interval between ads after initial breakpoints (default: 8)
 * @param removeAds If true, returns items without ads
 * @return List with ads interleaved at breakpoints
 */
fun <T> interleaveAdsCustom(
    items: List<T>,
    initialBreakpoints: List<Int> = listOf(4, 8),
    interval: Int = 8,
    removeAds: Boolean = false
): List<ListEntryWithAd<T>> {
    if (items.isEmpty()) return emptyList()
    if (removeAds) return items.map { ListEntryWithAd.Item(it) }

    return try {
        val breakpoints = buildCustomBreakpoints(items.size, initialBreakpoints, interval)
        val result = ArrayList<ListEntryWithAd<T>>(items.size + breakpoints.size)
        var adIndex = 0

        items.forEachIndexed { idx, item ->
            result += ListEntryWithAd.Item(item)
            if ((idx + 1) in breakpoints) {
                result += ListEntryWithAd.Ad(adIndex++)
            }
        }

        result
    } catch (e: Exception) {
        items.map { ListEntryWithAd.Item(it) }
    }
}

/**
 * Build breakpoints with default pattern: 4, 8, 16, 24, 32...
 * FIX: Optimized using generateSequence
 */
private fun buildBreakpoints(itemCount: Int): Set<Int> = buildSet {
    add(4)
    generateSequence(8) { it + 8 }
        .takeWhile { it <= itemCount }
        .forEach { add(it) }
}

/**
 * Build breakpoints with custom initial values and interval.
 * FIX: Optimized using generateSequence
 */
private fun buildCustomBreakpoints(
    itemCount: Int,
    initial: List<Int>,
    interval: Int
): Set<Int> {
    if (initial.isEmpty()) return emptySet()
    if (interval <= 0) return initial.filter { it <= itemCount }.toSet()

    val maxInitial = initial.maxOrNull() ?: return emptySet()

    return buildSet {
        // Add initial breakpoints that fit within item count
        initial.filter { it <= itemCount }.forEach { add(it) }

        // Add subsequent breakpoints at regular intervals
        generateSequence(maxInitial + interval) { it + interval }
            .takeWhile { it <= itemCount }
            .forEach { add(it) }
    }
}

/**
 * Calculate how many ads will be shown for a given item count.
 */
fun calculateAdCount(itemCount: Int, removeAds: Boolean = false): Int {
    if (removeAds || itemCount == 0) return 0
    return buildBreakpoints(itemCount).size
}

/**
 * Calculate how many ads will be shown with custom breakpoints.
 */
fun calculateAdCountCustom(
    itemCount: Int,
    initialBreakpoints: List<Int> = listOf(4, 8),
    interval: Int = 8,
    removeAds: Boolean = false
): Int {
    if (removeAds || itemCount == 0) return 0
    return buildCustomBreakpoints(itemCount, initialBreakpoints, interval).size
}