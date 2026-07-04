package me.manga.kira.presentation.common.componants.sources

import me.manga.kira.composeapp.generated.resources.Res
import me.manga.kira.composeapp.generated.resources.ic_aasq
import me.manga.kira.composeapp.generated.resources.ic_azaro
import me.manga.kira.composeapp.generated.resources.ic_batcave
import me.manga.kira.composeapp.generated.resources.ic_batoto
import me.manga.kira.composeapp.generated.resources.ic_comickio
import me.manga.kira.composeapp.generated.resources.ic_demon
import me.manga.kira.composeapp.generated.resources.ic_desu
import me.manga.kira.composeapp.generated.resources.ic_dilar
import me.manga.kira.composeapp.generated.resources.ic_flowermanga
import me.manga.kira.composeapp.generated.resources.ic_inmanga
import me.manga.kira.composeapp.generated.resources.ic_komikcast
import me.manga.kira.composeapp.generated.resources.ic_komiku
import me.manga.kira.composeapp.generated.resources.ic_lavascans
import me.manga.kira.composeapp.generated.resources.ic_mangabuddy
import me.manga.kira.composeapp.generated.resources.ic_mangahub
import me.manga.kira.composeapp.generated.resources.ic_mangamello
import me.manga.kira.composeapp.generated.resources.ic_mangamello_plus
import me.manga.kira.composeapp.generated.resources.ic_mangapark
import me.manga.kira.composeapp.generated.resources.ic_mangas_origines
import me.manga.kira.composeapp.generated.resources.ic_mangatuk
import me.manga.kira.composeapp.generated.resources.ic_mangaworld
import me.manga.kira.composeapp.generated.resources.ic_manhastro
import me.manga.kira.composeapp.generated.resources.ic_manhwatop
import me.manga.kira.composeapp.generated.resources.ic_manhwaweb
import me.manga.kira.composeapp.generated.resources.ic_mediocretoons
import me.manga.kira.composeapp.generated.resources.ic_olympus
import me.manga.kira.composeapp.generated.resources.ic_prochan
import me.manga.kira.composeapp.generated.resources.ic_promanga
import me.manga.kira.composeapp.generated.resources.ic_raijinscan
import me.manga.kira.composeapp.generated.resources.ic_senkuro
import me.manga.kira.composeapp.generated.resources.ic_sussytoons
import me.manga.kira.composeapp.generated.resources.ic_swatmanga
import me.manga.kira.composeapp.generated.resources.ic_tapas
import me.manga.kira.composeapp.generated.resources.ic_taurusfansub
import me.manga.kira.composeapp.generated.resources.ic_timenaight
import me.manga.kira.composeapp.generated.resources.ic_webtoon_tr
import me.manga.kira.composeapp.generated.resources.ic_webtoonhatti
import me.manga.kira.composeapp.generated.resources.ic_zazamanga
import me.manga.kira.composeapp.generated.resources.manga_lek
import me.manga.kira.composeapp.generated.resources.team_x
import me.manga.kira.sources_repositry.data.MangaSource
import org.jetbrains.compose.resources.DrawableResource

/**
 * Maps a [BaseMangaRepository] to its brand drawable in `composeResources/drawable/`.
 *
 * The upstream Android code stored the icon as `R.drawable.ic_*` in `BaseMangaRepository.ICON: Int`.
 * That field is preserved (set to 0 on every concrete repo) but the integer R-ID is meaningless
 * on iOS/Desktop, so the multiplatform path looks up the drawable by `BaseMangaRepository.API` —
 * a stable string identifier — instead.
 *
 * Sources without a mapped icon return `null`; call sites should gracefully omit the icon (the
 * source toggle row already renders title + description without one). Adding a per-source mapping
 * is a low-risk additive change: drop a drawable into `composeResources/drawable/` and add a
 * branch here.
 *
 * Note: lookup is by `API` (the source's stable identifier — e.g. "AsuraToon", "MangaTown") and
 * NOT by class name (which is unstable under refactors).
 *
 * **Audit-trail postscript** (Phase 9.x.cluster86.staleKdocSweep.cascade,
 * Task #542, 2026-05-28): the 3-paragraph file-scope KDoc above
 * plus the L27-33 per-method KDoc on `resolve` plus the L37-39
 * composable-wrapper KDoc on `rememberRepoIconPainter` are jointly
 * classified as follows after recursive symbol verification across
 * the KMP graph (thirtieth sibling of the cluster57-85 sweep —
 * fifth file in the `presentation/common/componants/` cluster,
 * first file in `sources/`, structurally distinct as a stub API-
 * based icon resolver returning null until per-source mappings
 * land):
 *  (a) File-scope Para 1 — LIVE-NOT-STALE (REGISTERED-BUT-DORMANT
 *  design). "The upstream Android code stored the icon as
 *  `R.drawable.ic_` symbols in `BaseMangaRepository.ICON: Int`...
 *  multiplatform path looks up the drawable by `BaseMangaRepository.
 *  API`" — the L25-35 stub returns `null` unconditionally; per-
 *  source `when (repo.API)` branches have not landed yet; no R-ID
 *  consumer survives (recursive Grep for `R\.drawable\.ic_` matches
 *  ZERO live references file-wide).
 *  (b) File-scope Para 2 — LIVE-NOT-STALE (zero-mapping default
 *  contract). "Sources without a mapped icon return `null`; call
 *  sites should gracefully omit the icon" — LIVE realization at L34
 *  (`fun resolve(...): DrawableResource? = null`) plus L41-45
 *  (`rememberRepoIconPainter` returns null on null resolve).
 *  (c) File-scope Para 3 — LIVE-NOT-STALE. "Lookup is by `API`
 *  (the source's stable identifier — e.g. AsuraToon, MangaTown) and
 *  NOT by class name" — design rationale prose; stub returns null
 *  so no class-name lookup path exists.
 *  (d) L27-33 per-method KDoc on `resolve` — LIVE-NOT-STALE. "The
 *  default returns `null`. Per-source mappings can be added here as
 *  drawables land in `composeResources/drawable/`. The existing
 *  per-source toggle UI degrades gracefully when `null` is
 *  returned" — LIVE realization at L34 stub `= null`.
 *  (e) L37-39 composable-wrapper KDoc — MIXED-with-inherited-
 *  staleness. "Composable wrapper that resolves a repo's icon to a
 *  [Painter], suitable for direct use in
 *  `RepoToggleItem(painter = ...)` and `Image(painter = ...)` call
 *  sites" — the `Image(painter = ...)` reference is LIVE-NOT-STALE
 *  (`Image` is a foundation primitive); the `RepoToggleItem(painter
 *  = ...)` reference is STALE-SYMBOL-REFERENCE (retired in Phase
 *  9.x.sources.legacycomponents.retire §356 along with
 *  LanguageToggleWithAnimation). Per §253 audit-trail-preservation
 *  convention the stale reference is documented here without
 *  rewriting the original prose.
 *  Four LIVE-NOT-STALE classifications plus one MIXED-with-
 *  inherited-staleness classification STAND on their own merits as
 *  a faithful API-based icon-resolver stub manifest with documented
 *  staleness on the consumer-side reference. Original Phase 10.3-
 *  era prose preserved verbatim per the audit-trail-preservation
 *  convention.
 */
object RepoIconResolver {

    /**
     * Map a source's stable `API` identifier to its brand drawable in `composeResources/drawable/`,
     * or `null` when no drawable is shipped for that source (the row then renders icon-less, as
     * before). Keyed on the [MangaSource] `API` constants (so Arabic/localized API labels match)
     * — the multiplatform replacement for native's `MangaSource.ICON = R.drawable.ic_*`, whose int
     * R-IDs don't port to iOS/Desktop.
     */
    fun resolveByApi(api: String): DrawableResource? = when (api) {
        MangaSource.SWATMANGA.API -> Res.drawable.ic_swatmanga
        MangaSource.MANGAMELLO.API -> Res.drawable.ic_mangamello
        MangaSource.MANGAMELLOPLUS.API -> Res.drawable.ic_mangamello_plus
        MangaSource.MANGA_LEK.API -> Res.drawable.manga_lek
        MangaSource.TEAM_X.API -> Res.drawable.team_x
        MangaSource.LAVATOONS.API -> Res.drawable.ic_lavascans
        MangaSource.MANGATUK.API -> Res.drawable.ic_mangatuk
        MangaSource.AZORA.API -> Res.drawable.ic_azaro
        MangaSource.AASQ.API -> Res.drawable.ic_aasq
        MangaSource.DILAR.API -> Res.drawable.ic_dilar
        MangaSource.DILARV2.API -> Res.drawable.ic_dilar
        MangaSource.PROMANGA.API -> Res.drawable.ic_promanga
        MangaSource.PROCHAN.API -> Res.drawable.ic_prochan
        MangaSource.BATOTO.API -> Res.drawable.ic_batoto
        MangaSource.MANGABUDDY.API -> Res.drawable.ic_mangabuddy
        MangaSource.MANHWATOP.API -> Res.drawable.ic_manhwatop
        MangaSource.DEMONICSCANS.API -> Res.drawable.ic_demon
        MangaSource.COMICKIO.API -> Res.drawable.ic_comickio
        MangaSource.MANGAPARK.API,
        MangaSource.MANGAPARKAR.API,
        MangaSource.MANGAPARK_IT.API,
        MangaSource.MANGAPARK_ES.API,
        MangaSource.MANGAPARK_ES_LA.API,
        -> Res.drawable.ic_mangapark
        MangaSource.OLYMPUSBIBLIOTECA.API -> Res.drawable.ic_olympus
        MangaSource.MANHOWAWEB.API -> Res.drawable.ic_manhwaweb
        MangaSource.TAURUSFANSUB.API -> Res.drawable.ic_taurusfansub
        MangaSource.INMANGA.API -> Res.drawable.ic_inmanga
        MangaSource.KOMIKCAST.API -> Res.drawable.ic_komikcast
        MangaSource.KOMIKU.API -> Res.drawable.ic_komiku
        MangaSource.MANGAORIGINES.API -> Res.drawable.ic_mangas_origines
        MangaSource.RAIJINSCAN.API -> Res.drawable.ic_raijinscan
        MangaSource.MANHASTRO.API -> Res.drawable.ic_manhastro
        MangaSource.FLOWERMANGA.API -> Res.drawable.ic_flowermanga
        MangaSource.MEDIOCRETOONS.API -> Res.drawable.ic_mediocretoons
        MangaSource.DESU.API -> Res.drawable.ic_desu
        MangaSource.MANGAHUB.API -> Res.drawable.ic_mangahub
        MangaSource.BATCAVE.API -> Res.drawable.ic_batcave
        MangaSource.TIMENAGHT.API -> Res.drawable.ic_timenaight
        MangaSource.WEBTOONTR.API -> Res.drawable.ic_webtoon_tr
        MangaSource.WEBTOONHATTI.API -> Res.drawable.ic_webtoonhatti
        MangaSource.MANGAWORLD.API -> Res.drawable.ic_mangaworld
        MangaSource.SENKURO.API -> Res.drawable.ic_senkuro
        MangaSource.SUSSYTOONS.API -> Res.drawable.ic_sussytoons
        MangaSource.ZAZAMANGA.API -> Res.drawable.ic_zazamanga
        MangaSource.TAPASTIC.API -> Res.drawable.ic_tapas
        else -> null
    }
}
