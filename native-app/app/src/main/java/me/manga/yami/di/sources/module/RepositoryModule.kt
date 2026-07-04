package me.manga.yamiapk.di.sources.module

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import me.manga.yamiapk.sources_repositry.BaseMangaRepository
import me.manga.yamiapk.sources_repositry.ar.azora.AasqRepositoryv2
import me.manga.yamiapk.sources_repositry.ar.azora.AzoraRepositoryv2
import me.manga.yamiapk.sources_repositry.ar.dilar.DilarRepository
import me.manga.yamiapk.sources_repositry.ar.dilar.v2.DilarV2Repository
import me.manga.yamiapk.sources_repositry.ar.lavatoon.LavatoonsRepositoryv2
import me.manga.yamiapk.sources_repositry.ar.mangalek.MangaLekRepositoryv2
import me.manga.yamiapk.sources_repositry.ar.mangamello.MangamelloRepository
import me.manga.yamiapk.sources_repositry.ar.mangamelloplus.MangamelloPlusRepository
import me.manga.yamiapk.sources_repositry.ar.mangapark.MangaParkRepositoryAr
import me.manga.yamiapk.sources_repositry.ar.mangatuk.MangatukRepository
import me.manga.yamiapk.sources_repositry.ar.promanga.ProMangaRepository
import me.manga.yamiapk.sources_repositry.ar.promanga.ProchanRepository
import me.manga.yamiapk.sources_repositry.ar.swatmanga.SwatMangaRepository
import me.manga.yamiapk.sources_repositry.ar.teamx.TeamXRepositoryv2
import me.manga.yamiapk.sources_repositry.en.batcave.BatcaveRepository
import me.manga.yamiapk.sources_repositry.en.batoto_en.BatotoEnRepositoryv2
import me.manga.yamiapk.sources_repositry.en.demonicscans.DemonicScansRepository
import me.manga.yamiapk.sources_repositry.en.mangabuddy.MangaBuddyRepositoryV2
import me.manga.yamiapk.sources_repositry.en.mangapark.MangaParkRepository
import me.manga.yamiapk.sources_repositry.en.manhwatop.ManhwatopRepositoryV2
import me.manga.yamiapk.sources_repositry.en.tapastic.TapasticRepository
import me.manga.yamiapk.sources_repositry.en.zazamanga.ZazamangaRepository
import me.manga.yamiapk.sources_repositry.es.inmanga.InMangaRepository
import me.manga.yamiapk.sources_repositry.es.mangapark.MangaParkRepositoryEs
import me.manga.yamiapk.sources_repositry.es.mangapark_la.MangaParkRepositoryEs419
import me.manga.yamiapk.sources_repositry.es.manhwaweb.ManhwawebEsRepository
import me.manga.yamiapk.sources_repositry.es.olympusbiblioteca.OlympusbibliotecaRepository
import me.manga.yamiapk.sources_repositry.es.taurusfansub.TaurusFansubEsRepository
import me.manga.yamiapk.sources_repositry.fr.manga_origine.MangaOrigineRepository
import me.manga.yamiapk.sources_repositry.fr.raijinscan.RaijinScanRepository
import me.manga.yamiapk.sources_repositry.`in`.komikcast.KomikCastRepository
import me.manga.yamiapk.sources_repositry.`in`.komiku.KomikuRepository
import me.manga.yamiapk.sources_repositry.it.mangapark.MangaParkRepositoryIt
import me.manga.yamiapk.sources_repositry.it.mangaworld.MangaworldItRepository
import me.manga.yamiapk.sources_repositry.pt.flowermanga.FlowerMangaRepository
import me.manga.yamiapk.sources_repositry.pt.manhastro.ManhastroRepository
import me.manga.yamiapk.sources_repositry.pt.mediocretoons.MediocretoonsRepository
import me.manga.yamiapk.sources_repositry.pt.sussytoons.SussytoonsRepository
import me.manga.yamiapk.sources_repositry.ru.desu.DesuRepository
import me.manga.yamiapk.sources_repositry.ru.mangahub.MangahubRepository
import me.manga.yamiapk.sources_repositry.ru.senkuro.SenkuroRepository
import me.manga.yamiapk.sources_repositry.tr.timenaight.TimenaightRepository
import me.manga.yamiapk.sources_repositry.tr.webtoonatti.WebtoonhattiRepository
import me.manga.yamiapk.sources_repositry.tr.webtoontr.WebtoontrRepository

@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryModule {
    @Binds @IntoSet abstract fun bindMangaLek(repo: MangaLekRepositoryv2): BaseMangaRepository
    @Binds @IntoSet abstract fun bindMangaSwat(repo: SwatMangaRepository): BaseMangaRepository
    @Binds @IntoSet abstract fun bindMangamello(repo: MangamelloRepository): BaseMangaRepository
    @Binds @IntoSet abstract fun bindMangamelloPlus(repo: MangamelloPlusRepository): BaseMangaRepository

    @Binds @IntoSet abstract fun bindTeamX(repo: TeamXRepositoryv2): BaseMangaRepository
    @Binds @IntoSet abstract fun bindLavatoons(repo: LavatoonsRepositoryv2): BaseMangaRepository
    @Binds @IntoSet abstract fun bindAzora(repo: AzoraRepositoryv2): BaseMangaRepository
    @Binds @IntoSet abstract fun bindBatotoEn(repo: BatotoEnRepositoryv2): BaseMangaRepository
    @Binds @IntoSet abstract fun bindMangaBuddyEn(repo: MangaBuddyRepositoryV2): BaseMangaRepository
    @Binds @IntoSet abstract fun bindManhwatopEn(repo: ManhwatopRepositoryV2): BaseMangaRepository
//    @Binds @IntoSet abstract fun bindReadcomiconlineEn(repo: ReadComicOnlineRepository): BaseMangaRepository
    @Binds @IntoSet abstract fun bindBatcaveEn(repo: BatcaveRepository): BaseMangaRepository
    //    @Binds @IntoSet abstract fun bindComickIOEn(repo: ComickRepository): BaseMangaRepository
    @Binds @IntoSet abstract fun bindAasq(repo: AasqRepositoryv2): BaseMangaRepository
    @Binds @IntoSet abstract fun bindDilar(repo: DilarRepository): BaseMangaRepository
    @Binds @IntoSet abstract fun bindDilarV2(repo: DilarV2Repository): BaseMangaRepository
    @Binds @IntoSet abstract fun bindMangatuk(repo: MangatukRepository): BaseMangaRepository

    @Binds @IntoSet abstract fun bindTapastic(repo: TapasticRepository): BaseMangaRepository


    @Binds @IntoSet abstract fun bindManhwawebEs(repo: ManhwawebEsRepository): BaseMangaRepository
    @Binds @IntoSet abstract fun bindTaurusFansubEs(repo: TaurusFansubEsRepository): BaseMangaRepository
    @Binds @IntoSet abstract fun bindKomikCastIn(repo: KomikCastRepository): BaseMangaRepository
    @Binds @IntoSet abstract fun bindKomikuIn(repo: KomikuRepository): BaseMangaRepository
    @Binds @IntoSet abstract fun bindMangaOrigineFr(repo: MangaOrigineRepository): BaseMangaRepository
    @Binds @IntoSet abstract fun bindRaijinScanFr(repo: RaijinScanRepository): BaseMangaRepository
    @Binds @IntoSet abstract fun bindManhastroPt(repo: ManhastroRepository): BaseMangaRepository
    @Binds @IntoSet abstract fun bindFlowerMangaPt(repo: FlowerMangaRepository): BaseMangaRepository
    @Binds @IntoSet abstract fun bindDesuMangaRu(repo: DesuRepository): BaseMangaRepository
    @Binds @IntoSet abstract fun bindMangahubRu(repo: MangahubRepository): BaseMangaRepository
    @Binds @IntoSet abstract fun bindMangaParkEn(repo: MangaParkRepository): BaseMangaRepository
    @Binds @IntoSet abstract fun bindProMangaAr(repo: ProMangaRepository): BaseMangaRepository
    @Binds @IntoSet abstract fun bindProMangachanAr(repo: ProchanRepository): BaseMangaRepository
    @Binds @IntoSet abstract fun bindMediocretoonsPt(repo: MediocretoonsRepository): BaseMangaRepository
    @Binds @IntoSet abstract fun bindInMangaEs(repo: InMangaRepository): BaseMangaRepository
    @Binds @IntoSet abstract fun bindOlympusbibliotecaEs(repo: OlympusbibliotecaRepository): BaseMangaRepository
    @Binds @IntoSet abstract fun bindDemonicscansEn(repo: DemonicScansRepository): BaseMangaRepository

    // Comick repositories (all languages)
//    @Binds @IntoSet abstract fun bindComickIOPt(repo: ComickRepositoryPtBr): BaseMangaRepository
//    @Binds @IntoSet abstract fun bindComickIOEsLa(repo: ComickRepositoryEs): BaseMangaRepository
//    @Binds @IntoSet abstract fun bindComickIORu(repo: ComickRepositoryRu): BaseMangaRepository
//    @Binds @IntoSet abstract fun bindComickIOFr(repo: ComickRepositoryFr): BaseMangaRepository
//    @Binds @IntoSet abstract fun bindComickIOId(repo: ComickRepositoryId): BaseMangaRepository
//    @Binds @IntoSet abstract fun bindComickIOTr(repo: ComickRepositoryTr): BaseMangaRepository
//    @Binds @IntoSet abstract fun bindComickIOIt(repo: ComickRepositoryIt): BaseMangaRepository
//    @Binds @IntoSet abstract fun bindComickIOAr(repo: ComickRepositoryAr): BaseMangaRepository

    @Binds @IntoSet abstract fun bindMangaParkAr(repo: MangaParkRepositoryAr): BaseMangaRepository
    @Binds @IntoSet abstract fun bindMangaParkIt(repo: MangaParkRepositoryIt): BaseMangaRepository
    @Binds @IntoSet abstract fun bindMangaParkEs(repo: MangaParkRepositoryEs): BaseMangaRepository
    @Binds @IntoSet abstract fun bindMangaParkEs419(repo: MangaParkRepositoryEs419): BaseMangaRepository

    @Binds @IntoSet abstract fun bindTimenaightTr(repo: TimenaightRepository): BaseMangaRepository
    @Binds @IntoSet abstract fun bindWebtoonTr(repo: WebtoontrRepository): BaseMangaRepository
    @Binds @IntoSet abstract fun bindWebtoonhattiTr(repo: WebtoonhattiRepository): BaseMangaRepository


    @Binds @IntoSet abstract fun bindMangaworldIt(repo: MangaworldItRepository): BaseMangaRepository
    @Binds @IntoSet abstract fun bindSussytoonsPt(repo: SussytoonsRepository): BaseMangaRepository
    @Binds @IntoSet abstract fun bindSenkuroRu(repo: SenkuroRepository): BaseMangaRepository
    @Binds @IntoSet abstract fun bindZazamangaEn(repo: ZazamangaRepository): BaseMangaRepository



}
