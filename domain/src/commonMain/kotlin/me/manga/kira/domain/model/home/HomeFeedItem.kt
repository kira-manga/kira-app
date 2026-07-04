package me.manga.kira.domain.model.home

/**
 * Pure-domain representation of a single manga entry in the Home feed (grid/list row).
 *
 * Mirrors the legacy `MangaItem` (`:shared/.../domain/model/MangaItem.kt`) **minus** framework
 * annotations (contract §4 forbids `@Serializable` / `@Parcelize` on domain entities — the DTO
 * mappers in `:data` add serialization concerns at the layer boundary).
 *
 * Field mapping vs legacy `MangaItem`:
 *  - `imageUrl` → [coverUrl] (renamed to match the rework [me.manga.kira.domain.model.Manga]
 *    convention; same meaning, empty string when the source ships no cover).
 *  - `chapters: List<ChapterItem>?` → [recentChapters]: a non-null (possibly empty) list of
 *    lightweight [HomeChapterRef]s. The legacy Home row renders chapter "chips" for the few most
 *    recent chapters and taps them straight into the Reader; the full embedded `ChapterItem`
 *    (with name/date/bookmark) is overkill for that surface, so the rework carries only the
 *    `(number, url, isDownloaded)` triple the chips actually use (locked decision H-§74-(1)).
 *  - `api` / `language` / `title` / `url` / `rating` / `genres` preserved verbatim. `language`
 *    is retained so the heart-sync use cases can key library membership on the same
 *    (api + language + title) triple the rest of the rework uses (locked decision H-§74-(2)).
 */
data class HomeFeedItem(
    /** Source API identifier (e.g. "MangaDex", per-language source slug). */
    val api: String,
    /** ISO-639-1 source language code ("en", "ar", …). */
    val language: String,
    /** Manga title as published by the source. */
    val title: String,
    /** Source detail-page URL — the canonical address for opening Details. */
    val url: String,
    /** Cover image URL — empty string when the source doesn't ship one. */
    val coverUrl: String,
    /** Source-supplied rating; null when the source doesn't expose one. */
    val rating: Int?,
    /** Genre tags as the source labels them. May be empty. */
    val genres: List<String>,
    /** Most-recent chapter chips rendered on the Home row; tap → Reader. May be empty. */
    val recentChapters: List<HomeChapterRef>,
)

/**
 * Stable, collision-resistant identity for LazyColumn/LazyGrid `key`s **and** feed de-duplication.
 *
 * Combines source + language + detail [url] + [title]. [url] is the per-manga canonical address, so
 * a paginated feed that re-surfaces the same manga on consecutive pages — e.g. a source ordered by
 * "last chapter added", where new chapters shift items across the page boundary — collapses to a
 * single row instead of crashing Compose with `Key "…" was already used`. [title] is appended as a
 * defensive tiebreaker (some legacy parsers leak a null/blank title via a platform type), and
 * [api]/[language] scope the key across a multi-source feed.
 *
 * Used by BOTH [me.manga.kira] `HomeViewModel` (`distinctBy`) and the `:ui` HomeScreen
 * (`LazyVerticalGrid`/`LazyColumn` `key`) — the two MUST produce the identical string, so the
 * single source of truth lives here on the domain model rather than being duplicated per layer.
 */
fun HomeFeedItem.feedKey(): String = "$api|$language|$url|$title"

/**
 * Lightweight chapter reference for the Home-row chapter chips.
 *
 * The minimal subset of the legacy `ChapterItem` the Home chip actually needs: the displayed
 * [number], the [url] to open in the Reader, and the [isDownloaded] flag that drives the chip's
 * "offline-available" affordance. Full chapter metadata (name, date, bookmark) lives on the
 * rework [me.manga.kira.domain.model.Chapter] in the Details/Reader surfaces, not here.
 */
data class HomeChapterRef(
    /** Chapter number as the source labels it ("12", "12.5" — opaque to domain). */
    val number: String,
    /** Source page URL — the canonical address for opening this chapter in the Reader. */
    val url: String,
    /** True when this chapter has been downloaded for offline reading. */
    val isDownloaded: Boolean,
)
