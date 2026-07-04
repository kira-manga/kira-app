package me.manga.kira.domain.model

import kotlinx.serialization.Serializable

// Migration note (Phase 4 batch 4.2): @Parcelize + Parcelable dropped — Android-only API; replaced
// with @Serializable. See ChapterImage.kt for rationale.
//
// Phase 9.y.mangainfo.fieldprune.cumulative (Task #417) — final drop:
// `ratingCount` / `otherNames` / `artist` / `tags` / `yearOfProduction` / `favoritesCount` were
// orphan reads (no display path, no use case, no mapper outflow) per the §245 audit. After the
// per-source-repository defaults-first sweep stripped every writer call site (Slices 2-11), the
// fields are removed here. The rework `:domain` type [MangaDetails] dropped these same fields and
// the `:data` mapper `LegacyMangaInfo.toDomain()` was updated in Slice 1.
@Serializable
data class MangaInfo(
    val api: String,
    val language: String,
    val url: String,
    val title: String,
    val imageUrl: String,
    val rating: String,
    val description: String,
    val author: String,
    val genres: List<String>,
    val status: String,
    val chapters: MutableList<ChapterItem>,
)

/*
 * §253 audit-trail postscript — cluster281 §253 sweep (2026-05-29)
 * Classification: LIVE / LEGACY (pre-rework :shared commonMain domain model, still wired).
 *
 * LIVE evidence (writers + abstract contract + mapper outflow, verified by grep this sweep):
 *   - WRITERS (constructor / factory call sites): per-source repositories return this type —
 *     WebtoontrRepository.kt:308, WebtoonhattiRepository.kt:304, TimenaightRepository.kt:270,
 *     MangahubRepository.kt:235, SenkuroRepository.kt:615 (MangaTachiyomiInfoDto.toMangaInfo
 *     at :606-:615). The .chapters MutableList is filled at combine time — see
 *     SeparatedDetailsSites.kt:157 + SeparatedDetailsSitesv2.kt:219 ("Fill the MangaInfo's
 *     .chapters field and emit Success").
 *   - ABSTRACT-CONTRACT surface: the fetch fan returns Flow State MangaInfo — BaseManga.kt:93
 *     (abstract override fetchMangaChaptersF(query): Flow State MangaInfo), and the
 *     extractMangaInfo() abstracts at NormalSites.kt:191, NormalSitesv2.kt:228,
 *     SeparatedDetailsSites.kt:249, SeparatedDetailsSitesv2.kt:265.
 *   - MAPPER outflow: MangaDetailsMappers.kt:60 internal fun LegacyMangaInfo.toDomain():
 *     MangaDetails (import alias at MangaDetailsMappers.kt:6) bridges into the rework :domain
 *     MangaDetails (MangaDetails.kt KDoc names THIS file as the legacy type it strangler-figs).
 *   No Koin binding — plain data class, instantiated inline by source-repo parsers.
 *
 * Delta-axes (this legacy model vs. the rework :domain MangaDetails it strangler-figs into):
 *   1. Platform API — @Parcelize + Parcelable (Android-only) dropped Phase 4 batch 4.2, replaced
 *      by kotlinx.serialization @Serializable (see top-of-file migration note + ChapterImage.kt).
 *   2. Field-prune lineage — Phase 9.y (Task #417) removed 6 orphan fields (ratingCount,
 *      otherNames, artist, tags, yearOfProduction, favoritesCount) after a per-source-repository
 *      defaults-first sweep stripped every writer; the rework MangaDetails never carried them and
 *      MangaDetailsMappers.kt's toDomain() dropped the 6 copy lines in the same prep commit.
 *   3. Mutability axis — chapters is a MutableList here (so the combine step can fill it after the
 *      info parse), whereas the rework MangaDetails uses immutable List Chapter (immutability is a
 *      rework invariant; see MangaDetails.kt delta note "MutableList ChapterItem -> List Chapter").
 *   4. Threading/dispatcher — none in the value type; the Flow State MangaInfo emission runs on
 *      IODispatcher inside each source repo's fetchMangaChaptersF.
 *   5. DI binding mechanism — none; value type, never injected.
 *
 * Nested-comment hazard check: this file has 0 pre-existing KDoc/comment openers (the head uses
 * line comments in the double-slash form); this appended block adds exactly one opener and one
 * closer, with zero interior comment delimiters in the prose. Balanced.
 */
