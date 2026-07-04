package me.manga.kira.domain.model.home

/**
 * Pure-domain representation of a single item in the Home "popular" carousel.
 *
 * Mirrors the legacy `PopularManga` (`:shared/.../domain/model/PopularManga.kt`) — a flat
 * display record the carousel renders — **minus** framework annotations (contract §4). The legacy
 * carousel (`MangaCarousel`) renders cover + title and taps into Details; it consumes exactly the
 * five legacy fields, so this type mirrors them 1:1 with the rework `imageUrl` → [coverUrl] rename
 * (same meaning) and adds no speculative fields.
 */
data class FeaturedManga(
    /** Source API identifier. */
    val api: String,
    /** ISO-639-1 source language code. */
    val language: String,
    /** Manga title as published by the source. */
    val title: String,
    /** Source detail-page URL — the canonical address for opening Details. */
    val url: String,
    /** Cover image URL — empty string when the source doesn't ship one. */
    val coverUrl: String,
)

/**
 * Stable, collision-resistant identity for the featured carousel's `LazyRow` `key` + de-duplication
 * — the carousel counterpart of [HomeFeedItem.feedKey]. Same rationale: a source's "popular" list
 * can re-surface the same manga or leak a null/blank [title] (declared non-null `String` but null at
 * runtime via a legacy parser's platform type), and a title-only key crashes Compose with a
 * duplicate-key `IllegalArgumentException`. [url] is the per-manga canonical id; [title] is a
 * defensive tiebreaker. The carousel `key` and the VM `distinctBy` MUST use this same string.
 */
fun FeaturedManga.feedKey(): String = "$api|$language|$url|$title"
