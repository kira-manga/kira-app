package me.manga.kira.domain.model

import kotlinx.serialization.Serializable

@Serializable
data class PopularManga(
    val api: String,
    val language: String,
    val title: String,
    val url: String,
    val imageUrl: String,
)

/*
 * §253 audit-trail postscript — cluster281 §253 sweep (2026-05-29)
 * Classification: LIVE / LEGACY (pre-rework :shared commonMain domain model, still wired).
 *
 * LIVE evidence (writers + abstract contract + UI consumer, verified by grep this sweep):
 *   - WRITERS (constructor call sites inside toPopularManga/toPopularMangaList helpers):
 *     WebtoontrRepository.kt:409, WebtoonhattiRepository.kt:416, TimenaightRepository.kt:374,
 *     MangahubRepository.kt:282, SenkuroRepository.kt:272 + :597 (two factories:
 *     List MangaNode.toPopularMangaList at :264 and MangaTachiyomiInfoDto.toPopularManga at :591).
 *   - ABSTRACT-CONTRACT surface: the home/popular fan returns Flow State List PopularManga —
 *     BaseManga.kt:88 (abstract override fetchPopularManga), NormalSites.kt:82,
 *     NormalSitesv2.kt:58, SeparatedDetailsSites.kt:68, SeparatedDetailsSitesv2.kt:62; the
 *     extractMangaList() abstracts (NormalSites.kt:190, SeparatedDetailsSites.kt:248, etc.)
 *     return List PopularManga.
 *   - UI consumer: HomeScreen.kt:188/:279/:452 popularManga: State List PopularManga — the
 *     legacy home carousel still renders this type via MangaCarousel.kt:106 items: List PopularManga.
 *   No Koin binding — plain data class, instantiated inline by source-repo parsers.
 *
 * Delta-axes (this legacy model's posture vs. the rework :domain home/list models):
 *   1. Platform API — pure kotlinx.serialization @Serializable value type; no Parcelable history
 *      to drop (this 5-field record never carried the @Parcelize annotation that ChapterItem /
 *      MangaInfo shed in Phase 4 batch 4.2), so it is KMP-portable as-is.
 *   2. Field shape — flat 5-field discriminator-plus-display record (api / language / title /
 *      url / imageUrl); api is the source-discriminator string (MangaSource.kt:220 names the
 *      MangaItem.api + MangaInfo.api discriminator family this aligns with).
 *   3. Mapper status — unlike ChapterItem/MangaInfo there is NO LegacyPopularManga.toDomain()
 *      mapper in MangaDetailsMappers.kt; the home/search rework surface still consumes this legacy
 *      type directly through the composeApp HomeScreen, so it has no strangler-fig boundary yet.
 *   4. Threading/dispatcher — none in the value type; emission runs on IODispatcher inside each
 *      source repo's fetchPopularManga.
 *   5. DI binding mechanism — none; value type, never injected.
 *
 * Nested-comment hazard check: this file has 0 pre-existing KDoc/comment openers (no block or
 * line comments above the data class); this appended block adds exactly one opener and one closer,
 * with zero interior comment delimiters in the prose. Balanced.
 */
