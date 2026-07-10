package me.manga.kira.sources.runtime

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
import org.jetbrains.compose.resources.DrawableResource

/**
 * The packaged-drawable icon registry (MangaSource decoupling, 2026-07 — replaces the enum-keyed
 * `RepoIconResolver` `when(api)` map). Maps the STABLE string keys that config stanzas reference
 * (`SourceConfig.icon.resourceKey`) to drawables in `composeResources/drawable/`.
 *
 * Contract (docs/sources/MANGASOURCE_DECOUPLING_PLAN.md §4):
 *  - This is an ASSET map, not a source list — membership implies nothing about discovery,
 *    identity, enabling, or routing. Several sources may share one key (e.g. the Dilar pair).
 *  - JSON carries only the key, never a generated Kotlin resource identifier.
 *  - A missing/unknown key resolves to `null` (the UI falls through to the remote URL, then the
 *    deterministic initials avatar) — never a crash.
 *  - Adding a packaged icon = drop the drawable in `composeResources/drawable/` + one entry here +
 *    reference the key from the stanza's `icon.resourceKey`. No `MangaSource` entry, ever.
 *
 * Keys must match the validator vocabulary `[a-z0-9_]{1,64}` (pinned by `SourceIconRegistryTest`,
 * which also rejects duplicate keys and pins that every stanza-referenced key resolves).
 */
object SourceIconRegistry {
    /** Declared as a list (not `mapOf`) so the duplicate-key test can see raw entries. */
    val entries: List<Pair<String, DrawableResource>> =
        listOf(
            // The 12 generic pilots' brand marks.
            "azora" to Res.drawable.ic_azaro,
            "mangamello" to Res.drawable.ic_mangamello,
            "mangamello_plus" to Res.drawable.ic_mangamello_plus,
            "swatmanga" to Res.drawable.ic_swatmanga,
            "lekmanga" to Res.drawable.manga_lek,
            "team_x" to Res.drawable.team_x,
            "dilar" to Res.drawable.ic_dilar,
            "3asq" to Res.drawable.ic_aasq,
            "demonicscans" to Res.drawable.ic_demon,
            "mangabuddy" to Res.drawable.ic_mangabuddy,
            "zazamanga" to Res.drawable.ic_zazamanga,
            "tapas" to Res.drawable.ic_tapas,
            // Legacy brand marks, kept resolvable so a stanza (or a future re-enable) can reference
            // them without any Kotlin change beyond this asset map.
            "lavatoons" to Res.drawable.ic_lavascans,
            "mangatuk" to Res.drawable.ic_mangatuk,
            "promanga" to Res.drawable.ic_promanga,
            "prochan" to Res.drawable.ic_prochan,
            "batoto" to Res.drawable.ic_batoto,
            "manhwatop" to Res.drawable.ic_manhwatop,
            "comick" to Res.drawable.ic_comickio,
            "mangapark" to Res.drawable.ic_mangapark,
            "olympus" to Res.drawable.ic_olympus,
            "manhwaweb" to Res.drawable.ic_manhwaweb,
            "taurusfansub" to Res.drawable.ic_taurusfansub,
            "inmanga" to Res.drawable.ic_inmanga,
            "komikcast" to Res.drawable.ic_komikcast,
            "komiku" to Res.drawable.ic_komiku,
            "manga_origines" to Res.drawable.ic_mangas_origines,
            "raijinscan" to Res.drawable.ic_raijinscan,
            "manhastro" to Res.drawable.ic_manhastro,
            "flowermanga" to Res.drawable.ic_flowermanga,
            "mediocretoons" to Res.drawable.ic_mediocretoons,
            "desu" to Res.drawable.ic_desu,
            "mangahub" to Res.drawable.ic_mangahub,
            "batcave" to Res.drawable.ic_batcave,
            "timenaight" to Res.drawable.ic_timenaight,
            "webtoon_tr" to Res.drawable.ic_webtoon_tr,
            "webtoonhatti" to Res.drawable.ic_webtoonhatti,
            "mangaworld" to Res.drawable.ic_mangaworld,
            "senkuro" to Res.drawable.ic_senkuro,
            "sussytoons" to Res.drawable.ic_sussytoons,
        )

    private val byKey: Map<String, DrawableResource> = entries.toMap()

    fun resolve(resourceKey: String): DrawableResource? = byKey[resourceKey]
}
