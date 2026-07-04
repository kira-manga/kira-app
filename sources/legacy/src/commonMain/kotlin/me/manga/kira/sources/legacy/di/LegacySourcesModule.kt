package me.manga.kira.sources.legacy.di

import me.manga.kira.presentation.features.library.domain.LibraryRepository
import me.manga.kira.presentation.features.repo_settings.domain.SourcesRepository
import me.manga.kira.sources_repositry.BaseMangaRepository
import me.manga.kira.sources_repositry.ar.azora.AasqRepositoryv2
import me.manga.kira.sources_repositry.ar.azora.AzoraRepositoryv2
import me.manga.kira.sources_repositry.ar.dilar.DilarRepository
import me.manga.kira.sources_repositry.ar.dilar.v2.DilarV2Repository
import me.manga.kira.sources_repositry.ar.lavatoon.LavatoonsRepositoryv2
import me.manga.kira.sources_repositry.ar.mangalek.MangaLekRepositoryv2
import me.manga.kira.sources_repositry.ar.mangamello.MangamelloRepository
import me.manga.kira.sources_repositry.ar.mangamelloplus.MangamelloPlusRepository
import me.manga.kira.sources_repositry.ar.mangapark.MangaParkRepositoryAr
import me.manga.kira.sources_repositry.ar.mangatuk.MangatukRepository
import me.manga.kira.sources_repositry.ar.promanga.ProMangaRepository
import me.manga.kira.sources_repositry.ar.promanga.ProchanRepository
import me.manga.kira.sources_repositry.ar.swatmanga.SwatMangaRepository
import me.manga.kira.sources_repositry.ar.teamx.TeamXRepositoryv2
import me.manga.kira.sources_repositry.en.batcave.BatcaveRepository
import me.manga.kira.sources_repositry.en.batoto_en.BatotoEnRepositoryv2
import me.manga.kira.sources_repositry.en.demonicscans.DemonicScansRepository
import me.manga.kira.sources_repositry.en.mangabuddy.MangaBuddyRepositoryV2
import me.manga.kira.sources_repositry.en.mangapark.MangaParkRepository
import me.manga.kira.sources_repositry.en.manhwatop.ManhwatopRepositoryV2
import me.manga.kira.sources_repositry.en.tapastic.TapasticRepository
import me.manga.kira.sources_repositry.en.zazamanga.ZazamangaRepository
import me.manga.kira.sources_repositry.es.inmanga.InMangaRepository
import me.manga.kira.sources_repositry.es.mangapark.MangaParkRepositoryEs
import me.manga.kira.sources_repositry.es.mangapark_la.MangaParkRepositoryEs419
import me.manga.kira.sources_repositry.es.manhwaweb.ManhwawebEsRepository
import me.manga.kira.sources_repositry.es.olympusbiblioteca.OlympusbibliotecaRepository
import me.manga.kira.sources_repositry.es.taurusfansub.TaurusFansubEsRepository
import me.manga.kira.sources_repositry.fr.manga_origine.MangaOrigineRepository
import me.manga.kira.sources_repositry.fr.raijinscan.RaijinScanRepository
import me.manga.kira.sources_repositry.`in`.komikcast.KomikCastRepository
import me.manga.kira.sources_repositry.`in`.komiku.KomikuRepository
import me.manga.kira.sources_repositry.it.mangaworld.MangaworldItRepository
import me.manga.kira.sources_repositry.pt.flowermanga.FlowerMangaRepository
import me.manga.kira.sources_repositry.pt.manhastro.ManhastroDadosStore
import me.manga.kira.sources_repositry.pt.manhastro.ManhastroRepository
import me.manga.kira.sources_repositry.pt.mediocretoons.MediocretoonsRepository
import me.manga.kira.sources_repositry.pt.sussytoons.SussytoonsRepository
import me.manga.kira.sources_repositry.ru.desu.DesuRepository
import me.manga.kira.sources_repositry.ru.mangahub.MangahubRepository
import me.manga.kira.sources_repositry.ru.senkuro.SenkuroRepository
import me.manga.kira.sources_repositry.tr.timenaight.TimenaightRepository
import me.manga.kira.sources_repositry.tr.webtoonatti.WebtoonhattiRepository
import me.manga.kira.sources_repositry.tr.webtoontr.WebtoontrRepository
import org.koin.core.module.Module
import org.koin.dsl.module

/**
 * Legacy hand-written per-source scraper bindings — relocated here (strangler-fig Phase 3) from
 * `:shared`'s `sharedModule`. Holds the 43 concrete-repo `factory` bindings (each injects
 * `ApiClient` + `DataStoreHelper` + `SourcesDao`), the per-process `ManhastroDadosStore`, and the
 * `Set<BaseMangaRepository>` multibinding (the active-repo registry).
 *
 * Wired into the graph via `allSharedModules()` in `:shared`. `SourcesRepository` (a `:shared` type)
 * stays in `sharedModule` and resolves this `Set` cross-module by type — the same pattern by which
 * `databaseModule()`/`remoteModule()` already feed `sharedModule`.
 *
 * The disabled upstream stubs (Comick variants, MangaParkIt, ReadComicOnline) are intentionally
 * excluded from both the factories and the Set, mirroring the source `RepositoryModule`.
 */
fun legacySourcesModule(): Module = module {

    // ---- Per-source repositories (Phase 7 ports) ----
    // Arabic sources
    factory { AzoraRepositoryv2(get(), get(), get()) }
    factory { AasqRepositoryv2(get(), get(), get()) }
    factory { DilarRepository(get(), get(), get()) }
    factory { DilarV2Repository(get(), get(), get()) }
    factory { LavatoonsRepositoryv2(get(), get(), get()) }
    factory { MangaLekRepositoryv2(get(), get(), get()) }
    factory { MangamelloRepository(get(), get(), get()) }
    factory { MangamelloPlusRepository(get(), get(), get()) }
    factory { MangaParkRepositoryAr(get(), get(), get()) }
    factory { MangatukRepository(get(), get(), get()) }
    factory { ProMangaRepository(get(), get(), get()) }
    factory { ProchanRepository(get(), get(), get()) }
    factory { SwatMangaRepository(get(), get(), get()) }
    factory { TeamXRepositoryv2(get(), get(), get()) }

    // English sources
    // Comick (EN) intentionally not factory-bound: it is excluded from the Set<BaseMangaRepository>
    // multibinding below, matching the commented-out @IntoSet line in the upstream RepositoryModule.
    factory { MangaParkRepository(get(), get(), get()) }
    factory { BatcaveRepository(get(), get(), get()) }
    factory { BatotoEnRepositoryv2(get(), get(), get()) }
    factory { DemonicScansRepository(get(), get(), get()) }
    factory { MangaBuddyRepositoryV2(get(), get(), get()) }
    factory { ManhwatopRepositoryV2(get(), get(), get()) }
    // ReadComicOnlineRepository — disabled upstream (file is a doc-only stub); skip.
    factory { TapasticRepository(get(), get(), get()) }
    factory { ZazamangaRepository(get(), get(), get()) }

    // Spanish sources (Comick ES/AR variants are disabled stubs — see file headers)
    factory { MangaParkRepositoryEs(get(), get(), get()) }
    factory { MangaParkRepositoryEs419(get(), get(), get()) }
    factory { InMangaRepository(get(), get(), get()) }
    factory { ManhwawebEsRepository(get(), get(), get()) }
    factory { OlympusbibliotecaRepository(get(), get(), get()) }
    factory { TaurusFansubEsRepository(get(), get(), get()) }

    // French sources
    factory { RaijinScanRepository(get(), get(), get()) }
    factory { MangaOrigineRepository(get(), get(), get()) }

    // Indonesian sources (ComickRepositoryId — disabled stub)
    factory { KomikCastRepository(get(), get(), get()) }
    factory { KomikuRepository(get(), get(), get()) }

    // Italian sources (ComickRepositoryIt and MangaParkRepositoryIt — disabled stubs)
    factory { MangaworldItRepository(get(), get(), get()) }

    // Portuguese sources (ComickRepositoryPtBr — disabled stub)
    factory { FlowerMangaRepository(get(), get(), get()) }
    // ManhastroRepository takes a 4th param: a per-process ManhastroDadosStore cache.
    single { ManhastroDadosStore() }
    factory { ManhastroRepository(get(), get(), get(), get()) }
    factory { MediocretoonsRepository(get(), get(), get()) }
    factory { SussytoonsRepository(get(), get(), get()) }

    // Russian sources (ComickRepositoryRu — disabled stub)
    factory { DesuRepository(get(), get(), get()) }
    factory { MangahubRepository(get(), get(), get()) }
    factory { SenkuroRepository(get(), get(), get()) }

    // Turkish sources (ComickRepositoryTr — disabled stub)
    factory { TimenaightRepository(get(), get(), get()) }
    factory { WebtoonhattiRepository(get(), get(), get()) }
    factory { WebtoontrRepository(get(), get(), get()) }

    // Set<BaseMangaRepository> — equivalent to Hilt's @IntoSet multibinding. Every source repo
    // that should appear in the active-repo picker and the home/search aggregation must be listed
    // here. Mirrors RepositoryModule.kt in the source tree (only repos that have a non-stub port
    // are included; the commented-out lines in the upstream module — Comick variants,
    // MangaParkIt, ReadComicOnline — are intentionally skipped here for the same reason they are
    // skipped in the per-source factory bindings above).
    single<Set<BaseMangaRepository>> {
        setOf(
            // Arabic
            get<AzoraRepositoryv2>(),
            get<AasqRepositoryv2>(),
            get<DilarRepository>(),
            get<DilarV2Repository>(),
            get<LavatoonsRepositoryv2>(),
            get<MangaLekRepositoryv2>(),
            get<MangamelloRepository>(),
            get<MangamelloPlusRepository>(),
            get<MangaParkRepositoryAr>(),
            get<MangatukRepository>(),
            get<ProMangaRepository>(),
            get<ProchanRepository>(),
            get<SwatMangaRepository>(),
            get<TeamXRepositoryv2>(),
            // English
            get<MangaParkRepository>(),
            get<BatcaveRepository>(),
            get<BatotoEnRepositoryv2>(),
            get<DemonicScansRepository>(),
            get<MangaBuddyRepositoryV2>(),
            get<ManhwatopRepositoryV2>(),
            get<TapasticRepository>(),
            get<ZazamangaRepository>(),
            // Spanish
            get<MangaParkRepositoryEs>(),
            get<MangaParkRepositoryEs419>(),
            get<InMangaRepository>(),
            get<ManhwawebEsRepository>(),
            get<OlympusbibliotecaRepository>(),
            get<TaurusFansubEsRepository>(),
            // French
            get<RaijinScanRepository>(),
            get<MangaOrigineRepository>(),
            // Indonesian
            get<KomikCastRepository>(),
            get<KomikuRepository>(),
            // Italian
            get<MangaworldItRepository>(),
            // Portuguese
            get<FlowerMangaRepository>(),
            get<ManhastroRepository>(),
            get<MediocretoonsRepository>(),
            get<SussytoonsRepository>(),
            // Russian
            get<DesuRepository>(),
            get<MangahubRepository>(),
            get<SenkuroRepository>(),
            // Turkish
            get<TimenaightRepository>(),
            get<WebtoonhattiRepository>(),
            get<WebtoontrRepository>(),
        )
    }

    // ---- Legacy repositories relocated from :shared's sharedModule (strangler-fig Phase 5) ----
    // SourcesRepository (the legacy source registry) resolves the Set<BaseMangaRepository> above
    // + SourcesDao (:data:local) + SharedPrefsHelper (:platform) + the app CoroutineScope (bound
    // cross-module). LibraryRepository wraps the Room DAOs (:data:local) + FileService (:platform).
    // Both are consumed by :data:download (engine) + :app; they live here — the module both those
    // consumers already depend on — rather than :data (which would cycle with :data:download).
    single { SourcesRepository(get(), get<Set<BaseMangaRepository>>(), get(), get()) }
    single { LibraryRepository(get(), get(), get(), get(), get(), get()) }
}
