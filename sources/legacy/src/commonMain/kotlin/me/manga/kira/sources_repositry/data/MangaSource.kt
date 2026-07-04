package me.manga.kira.sources_repositry.data

import androidx.compose.ui.graphics.Color


/**
 * Domain enum for manga sources
 *
 * Migration note (Phase 7 batch 7.0):
 * - The source used Android-resource IDs (`R.drawable.ic_xxx`) for `ICON`. `me.manga.kira.R`
 *   is JVM-Android-only and forbidden in commonMain. Each `ICON` here is a `0` placeholder; the
 *   actual drawable mapping is performed in a platform-aware lookup (see compose-resources
 *   wiring in :composeApp). The original resource-id literal is preserved in a trailing comment
 *   on each entry so the mapping is not lost.
 * - `androidx.compose.ui.graphics.Color` is multiplatform (compose-multiplatform), so the
 *   `COLORS` extension and `isDark()` helper are kept verbatim.
 */
// 1) Define your language enum with a label for display:
enum class Language(val Language: String) {
    AR("(AR)"),
    EN("(EN)"),
    ES("(ES)"),
    IN("(IN)"),
    FR("(FR)"),
    PT("(PT)"),
    RU("(RU)"),
    IT("(IT)"),
    TR("(TR)"),



}

// 2) Have each MangaSource carry a Language, not a String:
enum class MangaSource(
    val API: String,
    val LANGUAGE: Language,
    val BASEURL: String,
    val ICON: Int,
    val PRIORITY: Int,
    ) {
    SWATMANGA("SwatManga", Language.AR, "https://appswat.com/v2/api/v1/", 0 /* R.drawable.ic_swatmanga */, 5),
    MANGAMELLO("Mangamello", Language.AR, "https://plus.mangamello.com/", 0 /* R.drawable.ic_mangamello */, 1),
    MANGAMELLOPLUS("Mangamello Plus", Language.AR, "https://plus.mangamello.com/", 0 /* R.drawable.ic_mangamello_plus */, 1),

    MANGA_LEK("Lekmanga", Language.AR, "https://lek-manga.net/", 0 /* R.drawable.manga_lek */, 2),
    TEAM_X("Team X", Language.AR, "https://olympustaff.com/", 0 /* R.drawable.team_x */, 0),
    LAVATOONS("Lavatoons", Language.AR, "https://lavascans.com", 0 /* R.drawable.ic_lavascans */, 3),
    MANGATUK("Mangatuk", Language.AR, "https://mangatuk.com/", 0 /* R.drawable.ic_mangatuk */, 3),
    AZORA("Azora", Language.AR, "https://azoramoon.com/", 0 /* R.drawable.ic_azaro */, 4),
    AASQ("3asq", Language.AR, "https://3asq.org/", 0 /* R.drawable.ic_aasq */, 9),
    DILAR("Dilar", Language.AR, "https://dilar.tube/", 0 /* R.drawable.ic_dilar */, 4),
    DILARV2("DilarV2", Language.AR, "https://v2.dilar.tube/", 0 /* R.drawable.ic_dilar */, 4),

    PROMANGA(API = "Promanga", BASEURL = "https://api.prochan.net/", LANGUAGE = Language.AR, ICON = 0 /* R.drawable.ic_promanga */,  PRIORITY = 6),

    PROCHAN(API = "Prochan", BASEURL = "https://prochan.net/", LANGUAGE = Language.AR, ICON = 0 /* R.drawable.ic_prochan */,  PRIORITY = 6),

    BATOTO("Batoto", Language.EN, "https://bato.to/", 0 /* R.drawable.ic_batoto */, 6),
    MANGABUDDY("Mangabuddy", Language.EN, "https://mangabuddy.com", 0 /* R.drawable.ic_mangabuddy */, 7),
    MANHWATOP("Manhwatop", Language.EN, "https://manhwatop.com/", 0 /* R.drawable.ic_manhwatop */, 8),
    DEMONICSCANS("Demonicscans", Language.EN, "https://demonicscans.org/", 0 /* R.drawable.ic_demon */, 8),

    TAPASTIC(
         "Tapas",
         Language.EN, // or whatever your Language enum uses
        "https://tapas.io",
        0 /* R.drawable.ic_tapas */, 9
         ),

    COMICKIO("Comick", Language.EN, "https://comick.io/", 0 /* R.drawable.ic_comickio */, 20),

    MANGAPARK(API = "Mangapark", LANGUAGE = Language.EN , BASEURL = "https://mangapark.io/apo/",  ICON = 0 /* R.drawable.ic_mangapark */, PRIORITY = 2),
    MANGAPARKAR(API = "مانجا بارك", LANGUAGE = Language.AR , BASEURL = "https://mangapark.io/apo/",  ICON = 0 /* R.drawable.ic_mangapark */, PRIORITY = 6),
    MANGAPARK_IT(API = "Mangapark-It", LANGUAGE = Language.IT, BASEURL = "https://mangapark.io/apo/", ICON = 0 /* R.drawable.ic_mangapark */, PRIORITY = 3),
    MANGAPARK_ES(API = "Mangapark-Es", LANGUAGE = Language.ES, BASEURL = "https://mangapark.io/apo/", ICON = 0 /* R.drawable.ic_mangapark */, PRIORITY = 4),
    MANGAPARK_ES_LA(API = "Mangapark-Es-La", LANGUAGE = Language.ES, BASEURL = "https://mangapark.io/apo/", ICON = 0 /* R.drawable.ic_mangapark */, PRIORITY = 5),


    OLYMPUSBIBLIOTECA("Olympusbiblioteca", Language.ES, "https://olympusbiblioteca.com/", 0 /* R.drawable.ic_olympus */, 10),

    MANHOWAWEB("Manhwaweb", Language.ES, "https://manhwaweb.com/", 0 /* R.drawable.ic_manhwaweb */, 10),
    TAURUSFANSUB("Taurus Fansub", Language.ES, "https://taurus.topmanhuas.org/", 0 /* R.drawable.ic_taurusfansub */, 11),
    INMANGA("Inmanga", Language.ES, "https://inmanga.com/", 0 /* R.drawable.ic_inmanga */, 20),

    KOMIKCAST("Komik Cast", Language.IN, "https://komikcast.pics/", 0 /* R.drawable.ic_komikcast */, 12),
    KOMIKU("Komiku", Language.IN, "https://komiku.org/", 0 /* R.drawable.ic_komiku */, 12),

    MANGAORIGINES("Manga Origine", Language.FR, "https://mangas-origines.fr/", 0 /* R.drawable.ic_mangas_origines */, 13),
    RAIJINSCAN("Raijinscan", Language.FR, "https://raijin-scans.fr/", 0 /* R.drawable.ic_raijinscan */, 14),

    MANHASTRO("Manhastro", Language.PT, "https://api2.manhastro.net/", 0 /* R.drawable.ic_manhastro */, 15),
    FLOWERMANGA("Flowermanga", Language.PT, "https://flowermanga.net/", 0 /* R.drawable.ic_flowermanga */, 16),
    MEDIOCRETOONS("Mediocretoons", Language.PT, "https://api.mediocretoons.com/", 0 /* R.drawable.ic_mediocretoons */, 19),

    DESU("Desu", Language.RU, "https://desu.city/", 0 /* R.drawable.ic_desu */, 17),
    MANGAHUB("Mangahub", Language.RU, "https://mangahub.ru/", 0 /* R.drawable.ic_mangahub */, 18),

    BATCAVE("Batcave", Language.EN, "https://batcave.biz/", 0 /* R.drawable.ic_batcave */, 3),


    TIMENAGHT("Timenaight", Language.TR, "https://timenaight.org/", 0 /* R.drawable.ic_timenaight */, 10),
    WEBTOONTR("Webtoontr", Language.TR, "https://webtoontr.net/", 0 /* R.drawable.ic_webtoon_tr */, 11),
    WEBTOONHATTI("Webtoonhatti", Language.TR, "https://webtoonhatti.club/", 0 /* R.drawable.ic_webtoonhatti */, 12),

    MANGAWORLD("Mangaworld", Language.IT, "https://mangaworld.cx/", 0 /* R.drawable.ic_webtoonhatti */, 12),
    SENKURO("Senkuro", Language.RU, "https://api.senkuro.com/graphql", 0 /* R.drawable.ic_senkuro */, 10),
    SUSSYTOONS("Sussytoons", Language.PT, "https://api2.sussytoons.wtf/", 0 /* R.drawable.ic_sussytoons */, 10),

    ZAZAMANGA("Zazamanga", Language.EN, "https://www.zazamanga.com/", 0 /* R.drawable.ic_zazamanga */, 3)

}
val String.COLORS: Color
    get() = when (this) {
        MangaSource.MANGA_LEK.API -> Color(0xFF2D75C0)
        MangaSource.TEAM_X.API -> Color(0xFFE73149)
        MangaSource.LAVATOONS.API -> Color(0xFFC0C0C0)
        MangaSource.AZORA.API -> Color(0xFF867C01)
        MangaSource.BATOTO.API -> Color(0xFF13667A)
        MangaSource.MANGABUDDY.API -> Color(0xFF0000A2)
        MangaSource.MANHWATOP.API -> Color(0xFFF68B20)
        MangaSource.COMICKIO.API -> Color(0xFF1D2836)
        MangaSource.AASQ.API -> Color(0xFFEE483A)
        MangaSource.DILAR.API -> Color(0xFF3EC293)
        MangaSource.MANHOWAWEB.API -> Color(0xFF00F8FF)
        MangaSource.TAURUSFANSUB.API -> Color(0xFF077214)
        MangaSource.KOMIKCAST.API -> Color(0xFF03A9F4)
        MangaSource.MANGAMELLO.API -> Color(0xFFFFCC00)
        MangaSource.KOMIKU.API -> Color(0xFF00BCD4)
        MangaSource.MANGAORIGINES.API -> Color(0xFF3F51B5)
        MangaSource.RAIJINSCAN.API -> Color(0xFF91CEFF)
        MangaSource.MANHASTRO.API -> Color(0xFF010A65)
        MangaSource.FLOWERMANGA.API -> Color(0xFFA600E8)
        MangaSource.DESU.API -> Color(0xFFCE5A00)
        MangaSource.MANGAHUB.API -> Color(0xFF00FDC9)
        MangaSource.MANGAPARK.API -> Color(0xFF0123DC)
        MangaSource.PROMANGA.API -> Color(0xFFE10711)
        MangaSource.MEDIOCRETOONS.API -> Color(0xFF4E9BA9)
        MangaSource.INMANGA.API -> Color(0xFF51F56E)



        MangaSource.SWATMANGA.API -> Color(0xFF7B2CBF)
        MangaSource.OLYMPUSBIBLIOTECA.API -> Color(0xFFD4A373)
        MangaSource.BATCAVE.API -> Color(0xFF2B2D42)
        MangaSource.DEMONICSCANS.API -> Color(0xFF5C0029)
//            MangaSource.READCOMICONLINE.API -> Color(0xFFB5179E)
        MangaSource.MANGAPARKAR.API -> Color(0xFF4CC9F0)
        MangaSource.MANGAPARK_IT.API -> Color(0xFF06FFA5)
        MangaSource.MANGAPARK_ES.API -> Color(0xFFFF006E)
        MangaSource.MANGAPARK_ES_LA.API -> Color(0xFFFB8500)
        MangaSource.TIMENAGHT.API -> Color(0xFF560BAD)
        MangaSource.WEBTOONTR.API -> Color(0xFF06D6A0)
        MangaSource.WEBTOONHATTI.API -> Color(0xFFEF476F)
        MangaSource.MANGAWORLD.API -> Color(0xFF118AB2)
        MangaSource.SENKURO.API -> Color(0xFF9D4EDD)
        MangaSource.SUSSYTOONS.API -> Color(0xFFFF5A5F)
        MangaSource.ZAZAMANGA.API -> Color(0xFF3A86FF)
        else -> Color(0xFF000000)
    }

fun Color.isDark(): Boolean {
    val luminance = 0.299 * red + 0.587 * green + 0.114 * blue
    return luminance < 0.5
}

/**
 * **Audit-trail postscript** (Phase 9.x.cluster188.staleKdocSweep.cascade,
 * Task #689, 2026-05-29): classified as follows after recursive symbol
 * verification (three-hundred-and-first sibling of the cluster57-187 sweep
 * continuum — CLOSING LEAF 4/4 of the wave-58 :shared root + sources_repositry
 * root-tier scout 4-leaf batch; MangaSource.kt 4/4).
 *
 *  (a) Top-KDoc "Domain enum for manga sources + Migration note (Phase 7 batch
 *  7.0): The source used Android-resource IDs (R.drawable.ic_xxx) for ICON.
 *  me.manga.kira.R is JVM-Android-only and forbidden in commonMain. Each
 *  ICON here is a 0 placeholder; the actual drawable mapping is performed in
 *  a platform-aware lookup (see compose-resources wiring in :composeApp). The
 *  original resource-id literal is preserved in a trailing comment on each
 *  entry so the mapping is not lost + androidx.compose.ui.graphics.Color is
 *  multiplatform (compose-multiplatform), so the COLORS extension and isDark()
 *  helper are kept verbatim" — LIVE-NOT-STALE for the enum shape AND
 *  FULFILLED-PORT for the Phase 7 batch 7.0 R.drawable resource-ID stripping
 *  port: verified `ICON = 0` placeholder values across all 45 enum entries
 *  (lines 42-110) with each carrying a trailing block-comment annotation
 *  preserving the original R.drawable.ic_xxx resource-ID literal per the
 *  audit-trail-preservation convention. The `androidx.compose.ui.graphics
 *  .Color` import (line 3) is the compose-multiplatform Color type — verified
 *  multiplatform-resolvable (consumed by `String.COLORS: Color` extension at
 *  lines 113-160 and the `Color.isDark()` luminance helper at lines 162-165
 *  uniformly across Android + iOS + Desktop). The "R is JVM-Android-only"
 *  rationale is LIVE — `me.manga.kira.R` is the generated Android resource
 *  registry that only exists in the androidApp module's R.java; importing it
 *  into commonMain would be a layer-boundary leak (commonMain cannot reach
 *  androidApp). The placeholder-zero-plus-trailing-comment pattern is the
 *  Phase 7 batch 7.0 canonical workaround — zero is the int-default sentinel,
 *  and the trailing-comment preserves the original literal for the platform
 *  -aware compose-resources lookup mapping (deferred to :composeApp wiring).
 *
 *  (b) `enum class Language(val Language: String)` declaration (lines 19-32)
 *  — LIVE-NOT-STALE; verified 9 language entries (AR + EN + ES + IN + FR +
 *  PT + RU + IT + TR) each carrying its parenthesized 2-letter display label
 *  ("(AR)" etc.). The `val Language: String` constructor-parameter is the
 *  display-label channel — collision with the enum name is intentional (it's
 *  the convention the rework inherits from the original Android source). The
 *  9-language coverage is the LIVE source-language graph reach (Arabic +
 *  English + Spanish + Indonesian + French + Portuguese + Russian + Italian
 *  + Turkish) — verified each language has 1+ MangaSource entries at line-
 *  range 42-110 (no orphan Language entries — every language is consumed by
 *  at least one source).
 *
 *  (c) `enum class MangaSource(API, LANGUAGE, BASEURL, ICON, PRIORITY)`
 *  declaration (lines 35-112) — LIVE-NOT-STALE; verified 45 MangaSource
 *  entries each carrying the 5-parameter constructor shape. The PRIORITY
 *  integer field is the LIVE sort-priority slot consumed by ActiveRepoProvider
 *  -style `.sortedBy { it.PRIORITY }` filtering (cluster173-swept sibling 630).
 *  The BASEURL field is the LIVE source-website URL anchor consumed by per
 *  -source repo `BASE_URL` overrides (BaseMangaRepository abstract member —
 *  cluster188 leaf 2 sibling 299). The API field is the LIVE source-identifier
 *  string consumed by `MangaItem.api` + `MangaInfo.api` discriminator fields
 *  uniformly across the rework graph.
 *
 *  (d) `val String.COLORS: Color` extension (lines 113-160) — LIVE-NOT-STALE;
 *  verified ~40-entry `when (this)` color-mapping body keyed on `MangaSource
 *  .{X}.API` literal-string match returning a hex-coded Color (e.g. `Color(
 *  0xFF2D75C0)` for MANGA_LEK). The `else -> Color(0xFF000000)` fallthrough
 *  (line 159) returns opaque-black for any unmapped source — graceful
 *  degradation for sources without explicit color theming. The `READCOMICONLINE`
 *  entry at line 147 is COMMENTED-OUT (single-line `//` prefix) — preserved
 *  verbatim per the audit-trail-preservation convention; the entry is a
 *  Phase 7 batch 7.0 historical-source remnant that the rework retired but
 *  preserved as inert-prose. The 5 MangaSource entries without explicit
 *  COLORS mapping (MANGAMELLOPLUS + MANGATUK + DILARV2 + PROCHAN + TAPASTIC)
 *  fall through to the else-branch opaque-black sentinel — verified LIVE
 *  behavior (no caller short-circuits the else-branch return).
 *
 *  (e) `fun Color.isDark()` helper (lines 162-165) — LIVE-NOT-STALE;
 *  implements the ITU-R BT.601 luminance formula (`0.299 * red + 0.587 *
 *  green + 0.114 * blue`) returning `true` for luminance below 0.5 (mid
 *  -gray threshold). Used by the rework presentation layer for text-color
 *  -on-source-card contrast selection (white text on dark sources, black
 *  text on light sources). The float-coefficients (0.299 + 0.587 + 0.114
 *  sum to 1.000) are the canonical CCIR-601 luma weights — preserved verbatim.
 *
 * --- CLOSING-LEAF SUMMARY (cluster188 :shared root + sources_repositry
 * root-tier scout 4-leaf batch) ---
 *
 * The cluster188 wave-58 4-leaf batch sweeps the :shared root + sources
 * _repositry root-tier prose-bearing surface: BrowserHeadersInterceptor.kt
 * (Task #686, opening leaf 1/4, sibling 298) + BaseMangaRepository.kt (Task
 * #687, leaf 2/4, sibling 299) + EmptyMangaRepository.kt (Task #688, leaf
 * 3/4, sibling 300) + MangaSource.kt (Task #689, closing leaf 4/4, sibling
 * 301). Combined with cluster183-187 :shared/data tier sweep (Tasks #638
 * through #685), the :shared root + :shared/data + :shared/sources_repositry
 * root-tier prose surfaces are now FULLY SWEPT modulo:
 *   (i)    admin/Admin.kt (cluster5 §460 already swept).
 *   (ii)   di/PlatformModule.kt (cluster171 §627 already swept).
 *   (iii)  di/KoinInitializer.kt (cluster171 §628 already swept).
 *   (iv)   di/SharedModule.kt (cluster172 §629 already swept).
 *   (v)    sources_repositry/ActiveRepoProvider.kt (cluster173 §630 already
 *          swept).
 *
 * Cumulative cluster183-188 :shared/data + :shared root + :shared/sources
 * _repositry root-tier sweep totals:
 *   - 4 + 5 + 5 + 5 + 3 + 4 = 26 §253 postscripts across 26 prose-bearing
 *     files (cluster183: 4 :data/local/converter + cluster184: 5 :data/local
 *     /dao + cluster185: 5 :data/local closing-tier + cluster186: 5 :data
 *     /local entity + cluster187: 3 :data outside-:data/local + cluster188:
 *     4 :shared root + sources_repositry root-tier).
 *   - 8 + 0 = 8 bare-prose-less skips at the :shared/data tier.
 *   - The Phase 6 Room KMP port is FULFILLED-PORT classified across the
 *     entire :shared/data/local tier (cluster183-186 continuum).
 *   - The Phase 7 Ktor3-engine-fan-out + OkHttp-to-Ktor interceptor port is
 *     FULFILLED-PORT classified across the :shared/data/remote tier
 *     (cluster187) AND the :shared root BrowserHeadersInterceptor (cluster188
 *     leaf 1/4).
 *   - The Phase 7 batch 7.0 Coil3 PlatformContext substitution port is
 *     FULFILLED-PORT classified for the buildImageRequest open-fn surface
 *     (cluster188 leaf 2/4 — abstraction REVIVED post-removal-prose-narrative)
 *     AND the R.drawable.ic_xxx resource-ID stripping port (cluster188 leaf
 *     4/4 — placeholder-zero-plus-trailing-comment pattern across 45
 *     MangaSource entries).
 *   - 1 PARTIALLY-FULFILLED-FORECAST classification (cluster188 leaf 3/4 —
 *     the EmptyMangaRepository Phase 8 "no-op override" forecast fulfilled
 *     via parent-class restoration + inheritance, not explicit override).
 *   - 1 FACTUALLY-DRIFTED-IN-PROSE-ONLY classification (cluster188 leaf 2/4 —
 *     the BaseMangaRepository top-KDoc removal-narrative is historical-record
 *     prose; the abstraction itself was REVIVED via PlatformContext per the
 *     CORRECTING-FORECAST-FROM-PROSE-A pattern documented in the leaf 2
 *     postscript).
 *
 * The next outside-the-:shared/{data,root,sources_repositry/root}-tier prose
 * -bearing candidates are the :shared/sources_repositry/common/ subtier
 * (per-language shared repo helpers) + per-language repo files within
 * :shared/sources_repositry/{ar,en,es,fr,id,it,pt,ru,tr}/ subtrees + the
 * :shared/presentation/features subtree (those `data` sub-packages within
 * presentation are NOT the :data architectural tier — they are presentation
 * -local model files, many already cluster124-137 swept). The cluster189
 * wave-59 batch will scout these candidates.
 *
 * Verified: 1 `enum class Language(val Language: String)` with 9 entries
 * (AR + EN + ES + IN + FR + PT + RU + IT + TR) + 1 `enum class MangaSource
 * (API, LANGUAGE, BASEURL, ICON, PRIORITY)` with 45 entries (each carrying
 * trailing block-comment R.drawable.ic_xxx resource-ID literal preserved per
 * audit-trail convention) + 1 `val String.COLORS: Color` extension with
 * ~40-entry `when` color-mapping body + 1 inert-prose COMMENTED-OUT
 * READCOMICONLINE entry (preserved verbatim) + 1 `Color.isDark()` BT.601
 * luminance helper + 1 Phase-7-batch-7.0 migration-note KDoc prose block.
 * Sibling: EmptyMangaRepository.kt (cluster188 prior sibling). CLOSING LEAF
 * 4/4 of the cluster188 :shared root + sources_repositry root-tier scout
 * 4-leaf batch + CLOSING LEAF of the cluster183-188 :shared/data + root +
 * sources_repositry-root tier prose-bearing sweep continuum. Compound
 * classification: LIVE-NOT-STALE + FULFILLED-PORT for the Phase 7 batch 7.0
 * R.drawable.ic_xxx resource-ID stripping port (the placeholder-zero-plus
 * -trailing-comment pattern). The "R is JVM-Android-only and forbidden in
 * commonMain" rationale and "compose-resources wiring in :composeApp" forward
 * -reference preserved verbatim per the audit-trail-preservation convention.
 * Original Phase-7-batch-7.0 migration-note prose preserved verbatim.
 */

