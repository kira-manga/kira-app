    package me.manga.yamiapk.sources_repositry.data

    import androidx.compose.ui.graphics.Color
    import me.manga.yamiapk.R


    /**
     * Domain enum for manga sources
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
        SWATMANGA("SwatManga", Language.AR, "https://appswat.com/v2/api/v1/",R.drawable.ic_swatmanga,5),
        MANGAMELLO("Mangamello", Language.AR, "https://plus.mangamello.com/",R.drawable.ic_mangamello,1),
        MANGAMELLOPLUS("Mangamello Plus", Language.AR, "https://plus.mangamello.com/",R.drawable.ic_mangamello_plus,1),

        MANGA_LEK("Lekmanga", Language.AR, "https://lekmanga.net/", R.drawable.manga_lek,2),
        TEAM_X("Team X", Language.AR, "https://olympustaff.com/",R.drawable.team_x,0),
        LAVATOONS("Lavatoons", Language.AR, "https://lavascans.com",R.drawable.ic_lavascans,3),
        MANGATUK("Mangatuk", Language.AR, "https://mangatuk.com/",R.drawable.ic_mangatuk,3),
        AZORA("Azora", Language.AR, "https://azoramoon.com/",R.drawable.ic_azaro,4),
        AASQ("3asq", Language.AR, "https://3asq.org/",R.drawable.ic_aasq,9),
        DILAR("Dilar", Language.AR, "https://dilar.tube/",R.drawable.ic_dilar,4),
        DILARV2("DilarV2", Language.AR, "https://v2.dilar.tube/",R.drawable.ic_dilar,4),

        PROMANGA(API = "Promanga", BASEURL = "https://api.prochan.net/", LANGUAGE = Language.AR, ICON = R.drawable.ic_promanga,  PRIORITY = 6),

        PROCHAN(API = "Prochan", BASEURL = "https://prochan.net/", LANGUAGE = Language.AR, ICON = R.drawable.ic_prochan,  PRIORITY = 6),

        BATOTO("Batoto", Language.EN, "https://bato.to/",R.drawable.ic_batoto,6),
        MANGABUDDY("Mangabuddy", Language.EN, "https://mangabuddy.com",R.drawable.ic_mangabuddy,7),
        MANHWATOP("Manhwatop", Language.EN, "https://manhwatop.com/",R.drawable.ic_manhwatop,8),
        DEMONICSCANS("Demonicscans", Language.EN, "https://demonicscans.org/",R.drawable.ic_demon,8),

        TAPASTIC(
             "Tapas",
             Language.EN, // or whatever your Language enum uses
            "https://tapas.io",
            R.drawable.ic_tapas,9
             ),

        COMICKIO("Comick", Language.EN, "https://comick.io/",R.drawable.ic_comickio,20),

        MANGAPARK(API = "Mangapark", LANGUAGE = Language.EN , BASEURL = "https://mangapark.io/apo/",  ICON = R.drawable.ic_mangapark, PRIORITY = 2),
        MANGAPARKAR(API = "مانجا بارك", LANGUAGE = Language.AR , BASEURL = "https://mangapark.io/apo/",  ICON = R.drawable.ic_mangapark, PRIORITY = 6),
        MANGAPARK_IT(API = "Mangapark-It", LANGUAGE = Language.IT, BASEURL = "https://mangapark.io/apo/", ICON = R.drawable.ic_mangapark, PRIORITY = 3),
        MANGAPARK_ES(API = "Mangapark-Es", LANGUAGE = Language.ES, BASEURL = "https://mangapark.io/apo/", ICON = R.drawable.ic_mangapark, PRIORITY = 4),
        MANGAPARK_ES_LA(API = "Mangapark-Es-La", LANGUAGE = Language.ES, BASEURL = "https://mangapark.io/apo/", ICON = R.drawable.ic_mangapark, PRIORITY = 5),


        OLYMPUSBIBLIOTECA("Olympusbiblioteca", Language.ES, "https://olympusbiblioteca.com/",R.drawable.ic_olympus,10),

        MANHOWAWEB("Manhwaweb", Language.ES, "https://manhwaweb.com/",R.drawable.ic_manhwaweb,10),
        TAURUSFANSUB("Taurus Fansub", Language.ES, "https://taurus.topmanhuas.org/",R.drawable.ic_taurusfansub,11),
        INMANGA("Inmanga", Language.ES, "https://inmanga.com/", R.drawable.ic_inmanga, 20),

        KOMIKCAST("Komik Cast", Language.IN, "https://komikcast.pics/",R.drawable.ic_komikcast,12),
        KOMIKU("Komiku", Language.IN, "https://komiku.org/",R.drawable.ic_komiku,12),

        MANGAORIGINES("Manga Origine", Language.FR, "https://mangas-origines.fr/",R.drawable.ic_mangas_origines,13),
        RAIJINSCAN("Raijinscan", Language.FR, "https://raijin-scans.fr/",R.drawable.ic_raijinscan,14),

        MANHASTRO("Manhastro", Language.PT, "https://api2.manhastro.net/",R.drawable.ic_manhastro,15),
        FLOWERMANGA("Flowermanga", Language.PT, "https://flowermanga.net/",R.drawable.ic_flowermanga,16),
        MEDIOCRETOONS("Mediocretoons", Language.PT, "https://api.mediocretoons.com/", R.drawable.ic_mediocretoons, 19),

        DESU("Desu", Language.RU, "https://desu.city/",R.drawable.ic_desu,17),
        MANGAHUB("Mangahub", Language.RU, "https://mangahub.ru/",R.drawable.ic_mangahub,18),

        BATCAVE("Batcave", Language.EN, "https://batcave.biz/",R.drawable.ic_batcave,3),


        TIMENAGHT("Timenaight", Language.TR, "https://timenaight.org/",R.drawable.ic_timenaight,10),
        WEBTOONTR("Webtoontr", Language.TR, "https://webtoontr.net/",R.drawable.ic_webtoon_tr,11),
        WEBTOONHATTI("Webtoonhatti", Language.TR, "https://webtoonhatti.club/",R.drawable.ic_webtoonhatti,12),

        MANGAWORLD("Mangaworld", Language.IT, "https://mangaworld.cx/",R.drawable.ic_webtoonhatti,12),
        SENKURO("Senkuro", Language.RU, "https://api.senkuro.com/graphql",R.drawable.ic_senkuro,10),
        SUSSYTOONS("Sussytoons", Language.PT, "https://api2.sussytoons.wtf/",R.drawable.ic_sussytoons,10),

        ZAZAMANGA("Zazamanga", Language.EN, "https://www.zazamanga.com/",R.drawable.ic_zazamanga,3)

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
